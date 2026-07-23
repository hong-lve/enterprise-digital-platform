import axios from 'axios';
import { message } from 'antd';
import { useAuthStore } from '../store/auth';

declare module 'axios' {
  export interface AxiosRequestConfig {
    skipErrorMessage?: boolean;
  }
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
  withCredentials: true
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    // A 401 on any call made *after* the initial session check (that one
    // opts out via skipErrorMessage and is handled by App.tsx's Protected)
    // means the session died mid-use - expired cookie, server restart, etc.
    // Previously this only showed a toast and left the page rendered as if
    // still logged in, so every subsequent click kept silently failing.
    if (status === 401 && !error.config?.skipErrorMessage && !window.location.pathname.startsWith('/login')) {
      useAuthStore.getState().clearSession();
      message.error('登录已过期，请重新登录');
      window.location.href = '/login';
      return Promise.reject(error);
    }
    if (error.config?.skipErrorMessage) {
      return Promise.reject(error);
    }
    const serverMessage = error.response?.data?.message;
    const text = status === 401
      ? '请先登录'
      : status === 403
        ? '没有权限'
        : status === 500
          ? '系统异常，请稍后再试'
          : serverMessage || '请求失败，请稍后再试';
    message.error(text);
    return Promise.reject(error);
  }
);
