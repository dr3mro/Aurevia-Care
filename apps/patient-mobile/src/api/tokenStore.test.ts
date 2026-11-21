import { jest } from '@jest/globals';

jest.mock('expo-secure-store', () => ({
  setItemAsync: jest.fn(),
  getItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import * as SecureStore from 'expo-secure-store';
import { saveToken, loadToken, clearToken } from './tokenStore';

describe('tokenStore', () => {
  it('saves token with requireAuthentication false', async () => {
    await saveToken('abc');
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith('session', 'abc', { requireAuthentication: false });
  });

  it('loads token', async () => {
    (SecureStore.getItemAsync as jest.Mock).mockResolvedValue('abc');
    await expect(loadToken()).resolves.toBe('abc');
    expect(SecureStore.getItemAsync).toHaveBeenCalledWith('session', { requireAuthentication: false });
  });

  it('clears token', async () => {
    await clearToken();
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith('session', { requireAuthentication: false });
  });
});
