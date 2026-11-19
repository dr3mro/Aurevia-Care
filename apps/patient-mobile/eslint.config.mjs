import { defineConfig } from 'eslint/config';
import expoConfig from 'eslint-config-expo/flat.js';
import { configs as tseslintConfigs } from 'typescript-eslint';

/**
 * Flat ESLint configuration for the patient-mobile module: Expo's shared
 * rules over the strict TypeScript set, plus explicit Jest and Node globals
 * for tests and configuration files.
 */
export default defineConfig(
  { ignores: ['dist/**', 'coverage/**', '.expo/**', 'build/**'] },
  ...tseslintConfigs.recommended,
  ...expoConfig,
  {
    files: ['**/*.ts', '**/*.tsx'],
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
  {
    files: ['**/*.test.ts', '**/*.test.tsx', 'jest.setup.ts'],
    languageOptions: {
      globals: {
        describe: 'readonly',
        it: 'readonly',
        test: 'readonly',
        expect: 'readonly',
        jest: 'readonly',
        beforeAll: 'readonly',
        beforeEach: 'readonly',
        afterAll: 'readonly',
        afterEach: 'readonly',
      },
    },
  },
  {
    files: ['**/*.js', '**/*.cjs'],
    languageOptions: {
      globals: {
        module: 'readonly',
        require: 'readonly',
        __dirname: 'readonly',
        __filename: 'readonly',
        process: 'readonly',
      },
    },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
);
