const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

function makeLogger(scope, minLevel) {
  const threshold = LEVELS[minLevel] ?? LEVELS.info;
  const log = (level, ...args) => {
    if (LEVELS[level] < threshold) return;
    const ts = new Date().toISOString();
    // eslint-disable-next-line no-console
    console[level === 'debug' ? 'log' : level](`[${ts}] [${scope}]`, ...args);
  };
  return {
    debug: (...a) => log('debug', ...a),
    info: (...a) => log('info', ...a),
    warn: (...a) => log('warn', ...a),
    error: (...a) => log('error', ...a),
  };
}

export function createLogger(scope) {
  return makeLogger(scope, process.env.LOG_LEVEL || 'info');
}
