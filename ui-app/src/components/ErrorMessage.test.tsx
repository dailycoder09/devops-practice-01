import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../api/client';
import { ErrorMessage } from './ErrorMessage';

describe('ErrorMessage', () => {
  it('says what was being done, then why, then what to do', () => {
    render(
      <ErrorMessage
        what="Could not load products"
        error={new ApiError(500, 'Internal Server Error', 'Database is down')}
        hint="Try later."
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load products. Database is down. Try later.');
  });

  it('ends with "Reference: <trace id>" when the server gave the request an id', () => {
    render(
      <ErrorMessage
        what="Could not load products"
        error={new ApiError(500, 'Internal Server Error', 'Database is down', '4bf92f35')}
        hint="Try later."
      />,
    );

    const sentence = screen.getByRole('alert').querySelector('p')!.textContent!;
    expect(sentence).toBe('Could not load products. Database is down. Try later. Reference: 4bf92f35');
  });

  it('has no reference without a trace id, and none for an error that is not from the API', () => {
    const { unmount } = render(
      <ErrorMessage what="Could not load" error={new ApiError(500, 'Internal Server Error')} />,
    );
    expect(screen.getByRole('alert')).not.toHaveTextContent('Reference');
    unmount();

    render(<ErrorMessage what="Could not load" error={new Error('boom')} />);
    expect(screen.getByRole('alert')).not.toHaveTextContent('Reference');
  });

  it('has a Try again button only when it can try again', async () => {
    const retry = vi.fn();
    const { rerender } = render(<ErrorMessage what="Could not load" error={new Error('x')} />);
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();

    rerender(<ErrorMessage what="Could not load" error={new Error('x')} onRetry={retry} />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });
});
