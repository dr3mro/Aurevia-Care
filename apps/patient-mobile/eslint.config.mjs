import { createRequire } from 'node:module';
import { defineConfig } from 'eslint/config';
import expoConfig from 'eslint-config-expo/flat.js';
import { configs as tseslintConfigs, plugin as tseslintPlugin } from 'typescript-eslint';

// Resolve Expo's own dependency even when npm installs it under Expo.
const expoRequire = createRequire(import.meta.resolve('eslint-config-expo/flat.js'));
const typescriptResolver = expoRequire.resolve('eslint-import-resolver-typescript');

/** Share the plugin instance and resolve Expo's import resolver from its package. */
function configureExpo(config) {
  const plugins = config.plugins;
  if (plugins?.['@typescript-eslint']) {
    return { ...config, plugins: { ...plugins, '@typescript-eslint': tseslintPlugin } };
  }

  const resolver = config.settings?.['import/resolver'];
  if (resolver?.typescript === true) {
    const { typescript, ...otherResolvers } = resolver;
    return {
      ...config,
      settings: {
        ...config.settings,
        'import/resolver': { ...otherResolvers, [typescriptResolver]: typescript },
      },
    };
  }

  return config;
}

/**
 * Flat ESLint configuration for the patient-mobile module: Expo's shared
 * rules over the strict TypeScript set, plus explicit Jest and Node globals
 * for tests and configuration files.
 */
export default defineConfig(
  { ignores: ['dist/**', 'coverage/**', '.expo/**', 'build/**'] },
  // Define the plugin once so ESLint does not see it redefined by Expo and
  // typescript-eslint configs.
  { plugins: { '@typescript-eslint': tseslintPlugin } },
  ...tseslintConfigs.recommended.map(({ plugins, ...config }) => config),
  ...expoConfig.map((config) => {
    const { plugins, ...rest } = configureExpo(config);
    return rest;
  }),
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
