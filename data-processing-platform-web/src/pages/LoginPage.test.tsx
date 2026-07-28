import '@testing-library/jest-dom/vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/auth';
import { useAuthStore } from '../store/auth';
import { LoginPage } from './LoginPage';

vi.mock('qrcode', () => ({
  default: { toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,fake') }
}));

// axios.isAxiosError() checks the isAxiosError marker rather than the
// real error class - a plain object with that flag is enough to drive
// LoginPage's status-based branching without going through real axios.
function axiosError(status: number | undefined, responseMessage?: string) {
  return {
    isAxiosError: true,
    response: status === undefined ? undefined : { status, data: { message: responseMessage } }
  };
}

function renderLoginPage() {
  return render(
    <MemoryRouter>
      <LoginPage successPath="/realtime/overview" />
    </MemoryRouter>
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.spyOn(message, 'error');
    vi.spyOn(message, 'success');
    useAuthStore.getState().clearSession();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('logs straight in when 2FA is not required', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockResolvedValue({ requires2fa: false, requiresSetup: false });
    vi.spyOn(authApi, 'getMeSilently').mockResolvedValue({
      id: 1, username: 'admin', displayName: '平台管理员', roles: ['ADMIN'], permissions: [], menus: []
    });

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));

    await waitFor(() => expect(useAuthStore.getState().ready).toBe(true));
    expect(useAuthStore.getState().displayName).toBe('平台管理员');
    // Should never show the 2FA screen for an account that doesn't need it.
    expect(screen.queryByText('双因子验证')).not.toBeInTheDocument();
  });

  it('goes to the verify step (not setup) when 2FA is already bound', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockResolvedValue({ requires2fa: true, requiresSetup: false, pendingToken: 'tok-1' });

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));

    expect(await screen.findByText('双因子验证')).toBeInTheDocument();
    expect(screen.getByText('请输入身份验证器 App 中显示的 6 位验证码。')).toBeInTheDocument();
    // Setup-only content (QR code / raw secret) must not leak into a plain verify.
    expect(screen.queryByAltText('TOTP 二维码')).not.toBeInTheDocument();
  });

  it('goes to the setup step with a QR code on first-ever 2FA login', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockResolvedValue({
      requires2fa: true,
      requiresSetup: true,
      pendingToken: 'tok-2',
      secret: 'ABCD1234',
      otpAuthUri: 'otpauth://totp/test'
    });

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));

    expect(await screen.findByAltText('TOTP 二维码')).toBeInTheDocument();
    expect(screen.getByText('ABCD1234')).toBeInTheDocument();
  });

  it('shows a dedicated message for wrong credentials (401) and stays on the password step', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockRejectedValue(axiosError(401));

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('用户名或密码错误'));
    expect(screen.getByRole('button', { name: /登录$/ })).toBeInTheDocument();
  });

  it('surfaces the server message as-is for a non-401 error response (e.g. rate limiting)', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockRejectedValue(axiosError(429, '登录尝试过多，请稍后再试'));

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('登录尝试过多，请稍后再试'));
  });

  it('shows a network-specific message when there is no HTTP response at all', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockRejectedValue(axiosError(undefined));

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('登录失败，请检查网络后重试'));
  });

  it('bounces back to the password step when the verify code has expired', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockResolvedValue({ requires2fa: true, requiresSetup: false, pendingToken: 'tok-3' });
    vi.spyOn(authApi, 'verifyTwoFactor').mockRejectedValue(axiosError(400, '验证码已过期'));

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));
    await screen.findByText('双因子验证');

    await user.type(screen.getByLabelText('验证码'), '123456');
    await user.click(screen.getByRole('button', { name: /验证并登录$/ }));

    await waitFor(() => expect(screen.queryByText('双因子验证')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: /登录$/ })).toBeInTheDocument();
  });

  it('keeps the user on the verify step for an ordinary wrong code', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'login').mockResolvedValue({ requires2fa: true, requiresSetup: false, pendingToken: 'tok-4' });
    vi.spyOn(authApi, 'verifyTwoFactor').mockRejectedValue(axiosError(400, '验证码错误'));

    renderLoginPage();
    await user.click(screen.getByRole('button', { name: /登录$/ }));
    await screen.findByText('双因子验证');

    await user.type(screen.getByLabelText('验证码'), '000000');
    await user.click(screen.getByRole('button', { name: /验证并登录$/ }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('验证码错误'));
    // A wrong-but-not-expired code should NOT kick the user back to square one.
    expect(screen.getByText('双因子验证')).toBeInTheDocument();
  });
});
