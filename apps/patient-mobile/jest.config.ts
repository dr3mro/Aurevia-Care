/**
 * Jest runs the patient-mobile acceptance tests through the Expo preset with
 * React Native Testing Library and fails the build below 100% coverage of all
 * production source files (PAT-01, PAT-02, PAT-03, UX-01).
 */
export default {
  preset: 'jest-expo',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  testMatch: ['<rootDir>/App.test.tsx', '<rootDir>/index.test.ts', '<rootDir>/app.config.test.ts', '<rootDir>/src/**/*.test.ts', '<rootDir>/src/**/*.test.tsx'],
  collectCoverageFrom: [
    '<rootDir>/App.tsx',
    '<rootDir>/index.ts',
    '<rootDir>/app.config.ts',
    '<rootDir>/src/**/*.{ts,tsx}',
    '!<rootDir>/src/**/*.d.ts',
    '!<rootDir>/**/*.test.{ts,tsx}',
    '!<rootDir>/jest.setup.ts',
  ],
  coverageThreshold: {
    global: {
      branches: 100,
      functions: 100,
      lines: 100,
      statements: 100,
    },
  },
};
