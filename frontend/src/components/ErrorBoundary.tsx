import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';

/**
 * Stops one broken render taking the whole application down to a blank page.
 *
 * React unmounts the entire root when a render throws and nothing catches it, so a single
 * unexpected shape from the API would leave the user with white space and no way back. This shows
 * what happened and offers a reload, which is the least a failure owes the person looking at it.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { failure: Error | null }> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { failure: null };
  }

  static getDerivedStateFromError(failure: Error): { failure: Error } {
    return { failure };
  }

  componentDidCatch(failure: Error, info: ErrorInfo): void {
    // Deliberately not swallowed: without this the stack is lost and the cause is unfindable.
    // eslint-disable-next-line no-console
    console.error('HrSched failed to render', failure, info.componentStack);
  }

  render(): ReactNode {
    const { failure } = this.state;
    if (failure === null) {
      return this.props.children;
    }
    return (
      <div className="failure" role="alert">
        <strong>Something in the interface broke.</strong>
        <p>{failure.message}</p>
        <button type="button" onClick={() => this.setState({ failure: null })}>
          Try again
        </button>
      </div>
    );
  }
}
