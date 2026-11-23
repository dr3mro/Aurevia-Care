/**
 * Staff web lint policy (ESLint 9 flat config, typescript-eslint 8).
 *
 * The bar is "strict, type-aware, zero warnings". Agents must write code that
 * passes these rules; they must never switch a rule off. The configuration
 * itself forbids inline `eslint-disable` directives and `@ts-ignore`, and
 * `scripts/check-constraints.py` rejects any change that edits this file to
 * weaken a rule. If a rule fires, fix the code.
 *
 * Version contract (kept in sync with package.json, exact pins):
 *   eslint 9.x (flat config, `defineConfig` from "eslint/config")
 *   typescript-eslint 8.x (peer: typescript >=4.8.4 <6.1.0)
 *   eslint-plugin-react 7.37.x, eslint-plugin-jsx-a11y 6.10.x,
 *   eslint-plugin-react-hooks 7.x, @eslint-community/eslint-plugin-eslint-comments 4.5.x
 */
import js from '@eslint/js';
import eslintComments from '@eslint-community/eslint-plugin-eslint-comments/configs';
import { defineConfig } from 'eslint/config';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

const sourceFiles = ['**/*.{ts,tsx}'];
const testFiles = ['tests/**/*.{ts,tsx}', 'e2e/**/*.ts'];
const nodeFiles = ['vite.config.ts', 'eslint.config.ts', 'e2e/**/*.ts'];

export default defineConfig(
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules/**', 'src/api/generated/**'],
  },
  {
    // Any directive that is no longer needed is itself an error.
    linterOptions: { reportUnusedDisableDirectives: 'error', reportUnusedInlineConfigs: 'error' },
  },
  { settings: { react: { version: 'detect' } } },
  js.configs.recommended,
  tseslint.configs.strictTypeChecked,
  tseslint.configs.stylisticTypeChecked,
  react.configs.flat.recommended,
  react.configs.flat['jsx-runtime'],
  jsxA11y.flatConfigs.strict,
  // No inline `eslint-disable` / `eslint-enable` / `eslint-env` comments at all.
  eslintComments.recommended,
  {
    files: sourceFiles,
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        project: ['./tsconfig.lint.json'],
        tsconfigRootDir: import.meta.dirname,
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,

      // Suppressions are not allowed: fix the code instead.
      '@eslint-community/eslint-comments/no-use': ['error', { allow: [] }],
      '@eslint-community/eslint-comments/require-description': 'error',
      '@typescript-eslint/ban-ts-comment': [
        'error',
        {
          'ts-ignore': true,
          'ts-nocheck': true,
          'ts-check': false,
          'ts-expect-error': 'allow-with-description',
          minimumDescriptionLength: 10,
        },
      ],

      // Type safety.
      '@typescript-eslint/no-explicit-any': ['error', { fixToUnknown: true }],
      '@typescript-eslint/no-non-null-assertion': 'error',
      '@typescript-eslint/consistent-type-imports': [
        'error',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
      ],
      '@typescript-eslint/consistent-type-exports': 'error',
      '@typescript-eslint/switch-exhaustiveness-check': 'error',
      '@typescript-eslint/strict-boolean-expressions': [
        'error',
        { allowString: false, allowNumber: false, allowNullableObject: true },
      ],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/prefer-readonly': 'error',
      '@typescript-eslint/no-import-type-side-effects': 'error',
      '@typescript-eslint/no-confusing-void-expression': ['error', { ignoreArrowShorthand: true }],
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],

      // React.
      'react/prop-types': 'off', // Props are typed by TypeScript.
      'react/jsx-no-leaked-render': ['error', { validStrategies: ['ternary'] }],
      'react/jsx-no-useless-fragment': 'error',
      'react/jsx-no-target-blank': 'error',
      'react/jsx-key': ['error', { checkFragmentShorthand: true }],
      'react/self-closing-comp': 'error',
      'react/function-component-definition': [
        'error',
        { namedComponents: 'function-declaration', unnamedComponents: 'arrow-function' },
      ],
      'react/hook-use-state': 'error',
      'react/no-array-index-key': 'error',
      'react/no-danger': 'error',
      'react/jsx-no-constructed-context-values': 'error',
      'react/void-dom-elements-no-children': 'error',

      // General correctness and maintainability.
      eqeqeq: ['error', 'always'],
      'no-console': 'error',
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-var': 'error',
      'prefer-const': 'error',
      'no-param-reassign': 'error',
      'no-throw-literal': 'error',
      'no-restricted-globals': ['error', 'event', 'name', 'status'],
      curly: ['error', 'all'],
      complexity: ['error', 12],
      'max-depth': ['error', 4],
      'max-params': ['error', 4],
      'max-lines-per-function': ['error', { max: 150, skipBlankLines: true, skipComments: true }],
    },
  },
  {
    // The config itself imports untyped plugins, so type-aware rules cannot analyse it.
    files: ['eslint.config.ts'],
    extends: [tseslint.configs.disableTypeChecked],
  },
  {
    // Plain Node scripts are outside the TypeScript project, so type-aware rules cannot run on them.
    files: ['**/*.{js,mjs,cjs}'],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: { globals: globals.node },
  },
  {
    // Tooling and end-to-end files run in Node.
    files: nodeFiles,
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
  {
    // Tests: same strictness, only structural size limits relax (describe blocks are long by nature).
    files: testFiles,
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      'max-lines-per-function': 'off',
    },
  },
);
