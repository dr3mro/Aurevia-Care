import tseslint from 'typescript-eslint';

// The root config governs only the shared frontend tooling. Each application
// owns its own strict lint config (apps/staff-web/eslint.config.ts,
// apps/patient-mobile) and runs it in its own check.sh. The frontend gate calls
// each module's lint command, so we must not double-lint app sources here.
export default tseslint.config(
  {
    ignores: [
      'node_modules/**',
      'build/**',
      '**/target/**',
      '**/dist/**',
      'coverage/**',
      'apps/**',
    ],
  },
  ...tseslint.configs.recommended.map((config) => ({
    ...config,
    files: ['tools/**/*.ts', 'vitest.config.ts'],
  })),
  ...tseslint.configs.strictTypeChecked.map((config) => ({
    ...config,
    files: ['tools/**/*.ts', 'vitest.config.ts'],
  })),
  {
    files: ['tools/**/*.ts', 'vitest.config.ts'],
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
