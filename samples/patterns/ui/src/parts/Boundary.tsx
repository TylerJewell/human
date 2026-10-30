import { Component, type ReactNode } from "react";

/** Keeps a failure in one step on that step: the page around it, and the other patterns, stay usable. */
export class Boundary extends Component<{ children: ReactNode }, { error?: Error }> {
  state: { error?: Error } = {};

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="pt-notyet" role="alert">
        <p>This step could not be drawn: {this.state.error.message}</p>
        <button type="button" className="pt-btn pt-btn--secondary" onClick={() => this.setState({ error: undefined })}>
          Try again
        </button>
      </div>
    );
  }
}
