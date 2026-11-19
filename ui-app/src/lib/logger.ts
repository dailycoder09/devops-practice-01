// A very small logger for the browser console.
//
// Every line starts with the level and a short time: "[WARN 13:45:12.120] message". The level is "info"
// unless the localStorage key "shop.logLevel" says otherwise ("debug" turns on the detailed lines; "warn" and
// "error" make it quieter). Open the browser console and run
//   localStorage.setItem('shop.logLevel', 'debug')
// to see route changes.
//
// What is never written: tokens, passwords, secrets, authorization headers and request or response bodies.
// A context object is cleaned before it is printed, so a careless caller cannot leak one by accident.

export type LogLevel = 'error' | 'warn' | 'info' | 'debug';

export const LOG_LEVEL_KEY = 'shop.logLevel';

const RANK: Record<LogLevel, number> = { error: 0, warn: 1, info: 2, debug: 3 };

const LEVELS = Object.keys(RANK) as LogLevel[];

/** Keys whose values are never printed, whatever they hold. */
const SECRET_KEY = /token|password|passwd|secret|authorization|credential|cookie|body/i;

const MAX_TEXT = 200;

/** The level now in force. Read on every call, so a change in the console works at once. */
export function currentLogLevel(): LogLevel {
  try {
    const stored = window.localStorage.getItem(LOG_LEVEL_KEY);
    if (stored && (LEVELS as string[]).includes(stored)) return stored as LogLevel;
  } catch {
    // Storage blocked by the browser: use the default.
  }
  return 'info';
}

/** 13:45:12.120, in local time. */
export function shortTime(date: Date = new Date()): string {
  const part = (value: number, width = 2) => String(value).padStart(width, '0');
  return `${part(date.getHours())}:${part(date.getMinutes())}:${part(date.getSeconds())}.${part(date.getMilliseconds(), 3)}`;
}

function clean(context: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(context)) {
    if (SECRET_KEY.test(key)) {
      result[key] = '[hidden]';
    } else if (typeof value === 'string') {
      result[key] = value.length > MAX_TEXT ? `${value.slice(0, MAX_TEXT)}…` : value;
    } else if (value === null || typeof value === 'number' || typeof value === 'boolean' || value === undefined) {
      result[key] = value;
    } else {
      // Objects and lists could hold anything. Say what they are, not what is inside.
      result[key] = Array.isArray(value) ? `[list of ${value.length}]` : '[object]';
    }
  }
  return result;
}

function write(level: LogLevel, message: string, context?: Record<string, unknown>): void {
  if (RANK[level] > RANK[currentLogLevel()]) return;
  const line = `[${level.toUpperCase()} ${shortTime()}] ${message}`;
  const sink =
    level === 'error'
      ? console.error
      : level === 'warn'
        ? console.warn
        : level === 'info'
          ? console.info
          : console.debug;
  if (context === undefined) sink(line);
  else sink(line, clean(context));
}

export const logger = {
  error: (message: string, context?: Record<string, unknown>) => write('error', message, context),
  warn: (message: string, context?: Record<string, unknown>) => write('warn', message, context),
  info: (message: string, context?: Record<string, unknown>) => write('info', message, context),
  debug: (message: string, context?: Record<string, unknown>) => write('debug', message, context),
};
