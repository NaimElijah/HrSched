import { Component, createRef } from 'react';
import type { ErrorInfo, ReactNode } from 'react';

const A_HEADING = 'h1, h2, h3, h4, h5, h6';

/**
 * Stops one broken render taking the whole application down to a blank page.
 *
 * React unmounts the entire root when a render throws and nothing catches it, so a single
 * unexpected shape from the API would leave the user with white space and no way back. This shows
 * what happened and offers a reload, which is the least a failure owes the person looking at it.
 *
 * The fallback replaces everything, landmarks included, so it is a `main` of its own. "Try again"
 * removes the button that had focus, so once the children are back focus moves to their first
 * heading, never to the page (#257).
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { failure: Error | null }> {
  private readonly contents = createRef<HTMLDivElement>();

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

  componentDidUpdate(_props: { children: ReactNode }, previous: { failure: Error | null }): void {
    if (previous.failure === null || this.state.failure !== null) {
      return;
    }
    const heading = this.contents.current?.querySelector<HTMLElement>(A_HEADING);
    if (heading === null || heading === undefined) {
      return;
    }
    // A heading is not focusable unless it says so, and the children's need not.
    if (!heading.hasAttribute('tabindex')) {
      heading.tabIndex = -1;
    }
    heading.focus();
  }

  render(): ReactNode {
    const { failure } = this.state;
    if (failure === null) {
      return <div ref={this.contents}>{this.props.children}</div>;
    }
    return (
      <main className="app">
        <div className="failure" role="alert">
          <strong>Something in the interface broke.</strong>
          <p>{failure.message}</p>
          <button type="button" onClick={() => this.setState({ failure: null })}>
            Try again
          </button>
        </div>
      </main>
    );
  }
}
