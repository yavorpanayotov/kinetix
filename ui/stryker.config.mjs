/**
 * Stryker mutation testing config. Run with `npm run test:mutation`.
 *
 * Initial scope: utils, api, and hooks (the most pure-function-heavy areas).
 * Components can be added later — they take longer to mutate and the v8 coverage
 * baseline should stabilise first.
 *
 * Mutation testing is not gated on PR — it is a weekly quality dashboard.
 * See .github/workflows/mutation.yml.
 */
export default {
  packageManager: 'npm',
  reporters: ['html', 'clear-text', 'progress', 'json'],
  testRunner: 'vitest',
  checkers: ['typescript'],
  tsconfigFile: 'tsconfig.app.json',
  vitest: {
    configFile: 'vite.config.ts',
  },
  mutate: [
    'src/utils/**/*.ts',
    'src/api/**/*.ts',
    'src/hooks/**/*.ts',
    '!src/**/*.test.ts',
    '!src/**/*.test.tsx',
    '!src/**/*.d.ts',
  ],
  thresholds: {
    high: 80,
    low: 60,
    break: null, // initial baseline — do not fail CI yet
  },
  timeoutMS: 60000,
  concurrency: 4,
  ignorePatterns: ['coverage/', 'dist/', 'playwright-report/', 'test-results/', 'node_modules/'],
};
