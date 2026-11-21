import * as SecureStore from 'expo-secure-store';

const SESSION_KEY = 'session';
const OPTIONS = { requireAuthentication: false } as const;

export async function saveToken(token: string): Promise<void> {
  await SecureStore.setItemAsync(SESSION_KEY, token, OPTIONS);
}

export async function loadToken(): Promise<string | null> {
  return SecureStore.getItemAsync(SESSION_KEY, OPTIONS);
}

export async function clearToken(): Promise<void> {
  await SecureStore.deleteItemAsync(SESSION_KEY, OPTIONS);
}
