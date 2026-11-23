import { createRequire } from 'node:module';
import { defineConfig } from 'eslint/config';
import expoConfig from 'eslint-config-expo/flat.js';
import { configs as tseslintConfigs, plugin as tseslintPlugin } from 'typescript-eslint';

// Resolve Expo's own dependency even when npm installs it under Expo.
const expoRequire = createRequire(import.meta.resolve('eslint-config-expo/flat.js'));
const typescriptResolver = expoRequire.resolve('eslint-import-resolver-typescript');
const typescriptParser = expoRequire.resolve('@typescript-eslint/parser');

/** Remove the shared TypeScript plugin from a config so it is only defined once. */
function withoutSharedPlugin(config) {
  if (!config.plugins?.['@typescript-eslint']) return config;
  const plugins = { ...config.plugins };
  delete plugins['@typescript-eslint'];
  return { ...config, plugins };
}

/**
 * Point Expo's bare import-parser name at an absolute module path.
 *
 * Expo's preset registers `import/parsers` with the key `@typescript-eslint/parser`,
 * but npm nests that package under Expo and typescript-eslint rather than hoisting
 * it, so eslint-plugin-import cannot resolve the bare name from the project root.
 * Keeping the same parser at an absolute path preserves every import rule while
 * letting the resolver load it. Input: one Expo flat config; output: that config
 * with a resolvable parser key, or unchanged when it sets no bare key.
 */
function withResolvableParser(config) {
  const parsers = config.settings?.['import/parsers'];
  const extensions = parsers?.['@typescript-eslint/parser'];
  if (extensions === undefined) return config;
  const otherParsers = { ...parsers };
  delete otherParsers['@typescript-eslint/parser'];
  return {
    ...config,
    settings: {
      ...config.settings,
      'import/parsers': { ...otherParsers, [typescriptParser]: extensions },
    },
  };
}

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
  // Define the TypeScript plugin once; keep other plugins (e.g. import) from
  // the source configs so their rules can resolve.
  { plugins: { '@typescript-eslint': tseslintPlugin } },
  ...tseslintConfigs.recommended.map((config) => withoutSharedPlugin(config)),
  ...expoConfig.map((config) => withoutSharedPlugin(withResolvableParser(configureExpo(config)))),
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
