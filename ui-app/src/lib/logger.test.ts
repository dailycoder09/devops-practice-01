import { currentLogLevel, LOG_LEVEL_KEY, logger, shortTime } from './logger';

const calls = (method: 'error' | 'warn' | 'info' | 'debug') => vi.mocked(console[method]).mock.calls;

describe('logger', () => {
  it('defaults to info: info, warn and error are written, debug is not', () => {
    logger.debug('detail');
    logger.info('hello');
    logger.warn('careful');
    logger.error('broken');

    expect(currentLogLevel()).toBe('info');
    expect(calls('debug')).toHaveLength(0);
    expect(calls('info')).toHaveLength(1);
    expect(calls('warn')).toHaveLength(1);
    expect(calls('error')).toHaveLength(1);
  });

  it('writes debug lines when localStorage shop.logLevel is "debug"', () => {
    localStorage.setItem(LOG_LEVEL_KEY, 'debug');

    logger.debug('route change');

    expect(currentLogLevel()).toBe('debug');
    expect(calls('debug')).toHaveLength(1);
  });

  it('reads the level on every call, so it can be changed in the console without a reload', () => {
    logger.debug('first');
    localStorage.setItem(LOG_LEVEL_KEY, 'debug');
    logger.debug('second');
    localStorage.removeItem(LOG_LEVEL_KEY);
    logger.debug('third');

    expect(calls('debug')).toHaveLength(1);
    expect(String(calls('debug')[0][0])).toContain('second');
  });

  it('can be made quieter, and falls back to info for a value it does not know', () => {
    localStorage.setItem(LOG_LEVEL_KEY, 'warn');
    logger.info('quiet');
    logger.warn('loud');
    expect(calls('info')).toHaveLength(0);
    expect(calls('warn')).toHaveLength(1);

    localStorage.setItem(LOG_LEVEL_KEY, 'shouting');
    expect(currentLogLevel()).toBe('info');
  });

  it('starts every line with the level and a short time, then the message', () => {
    logger.warn('Request failed');
    logger.error('Request failed');
    logger.info('Logged in');

    expect(String(calls('warn')[0][0])).toMatch(/^\[WARN \d\d:\d\d:\d\d\.\d{3}\] Request failed$/);
    expect(String(calls('error')[0][0])).toMatch(/^\[ERROR \d\d:\d\d:\d\d\.\d{3}\] Request failed$/);
    expect(String(calls('info')[0][0])).toMatch(/^\[INFO \d\d:\d\d:\d\d\.\d{3}\] Logged in$/);
  });

  it('uses the matching console method for each level', () => {
    localStorage.setItem(LOG_LEVEL_KEY, 'debug');
    logger.error('e');
    logger.warn('w');
    logger.info('i');
    logger.debug('d');

    expect([calls('error'), calls('warn'), calls('info'), calls('debug')].map((list) => list.length)).toEqual([
      1, 1, 1, 1,
    ]);
  });

  it('prints the context after the line', () => {
    logger.warn('Request failed', { method: 'GET', path: '/api/cart', status: 404, traceId: 'abc123' });

    expect(calls('warn')[0][1]).toEqual({ method: 'GET', path: '/api/cart', status: 404, traceId: 'abc123' });
  });

  it('never prints a token, a password, a secret, an authorization header or a request body', () => {
    logger.info('Sending', {
      token: 'eyJhbGciOiJIUzI1NiJ9.secret.part',
      accessToken: 'abc',
      password: 'hunter2',
      Authorization: 'Bearer abc',
      secret: 's3cret',
      requestBody: '{"password":"hunter2"}',
      body: '{"a":1}',
      status: 200,
    });

    const printed = JSON.stringify(calls('info')[0]);
    expect(printed).not.toContain('eyJhbGci');
    expect(printed).not.toContain('hunter2');
    expect(printed).not.toContain('Bearer');
    expect(printed).not.toContain('s3cret');
    expect(printed).not.toContain('"a":1');
    expect(calls('info')[0][1]).toMatchObject({
      token: '[hidden]',
      password: '[hidden]',
      Authorization: '[hidden]',
      status: 200,
    });
  });

  it('does not print the inside of an object or a list, and shortens a very long text', () => {
    logger.info('Context', {
      user: { email: 'jane@example.com', password: 'x' },
      items: [1, 2, 3],
      long: 'a'.repeat(500),
    });

    const context = calls('info')[0][1] as Record<string, string>;
    expect(context.user).toBe('[object]');
    expect(context.items).toBe('[list of 3]');
    expect(context.long.length).toBeLessThan(250);
    expect(JSON.stringify(context)).not.toContain('jane@example.com');
  });

  it('keeps working when the browser blocks storage', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(currentLogLevel()).toBe('info');
    logger.info('still fine');
    expect(calls('info')).toHaveLength(1);
    getItem.mockRestore();
  });
});

describe('shortTime', () => {
  it('shows hours, minutes, seconds and milliseconds with leading zeros', () => {
    expect(shortTime(new Date(2026, 10, 19, 4, 5, 6, 7))).toBe('04:05:06.007');
  });
});
