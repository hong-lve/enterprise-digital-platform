import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// React Testing Library's auto-cleanup registers itself against a global
// afterEach, which only exists when vitest's `globals` option is on - this
// project deliberately keeps globals off (explicit imports everywhere else),
// so without this, every test file's DOM from one test bleeds into the
// next and later tests start failing on "multiple elements found".
afterEach(() => {
  cleanup();
});

// jsdom has no matchMedia implementation - antd's grid/breakpoint hooks
// (Row/Col, used by Form.Item's layout) call it unconditionally on mount,
// so every antd form crashes in tests without this.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false
  })
});
