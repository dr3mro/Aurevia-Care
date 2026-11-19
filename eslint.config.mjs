import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['node_modules/**', 'build/**', '**/target/**', '**/dist/**', 'coverage/**'] },
  // JavaScript config/tooling files: base rules only, never typed rules.
  ...tseslint.configs.recommended.map((config) => ({
    ...config,
    files: ['**/*.js', '**/*.jsx', '**/*.mjs', '**/*.cjs'],
  })),
  // TypeScript application sources: strict typed linting per AGENTS.md.
  ...tseslint.configs.strictTypeChecked.map((config) => ({
    ...config,
    files: ['**/*.ts', '**/*.tsx'],
  })),
  {
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/ban-ts-comment': ['error', { 'ts-ignore': true }],
      '@typescript-eslint/no-non-null-assertion': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
);
