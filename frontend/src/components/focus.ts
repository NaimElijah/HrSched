/**
 * Moves focus to the first heading in `container` that `selector` matches (#257, C4).
 *
 * A heading takes focus only with a `tabIndex`, and the screens give theirs none, so one is given
 * here: `-1`, which lets a script focus it without adding it to the tab order.
 *
 * @param container where to look; null, as an unattached ref is, focuses nothing
 * @param selector which heading; the first `h2`, the one every view opens with, by default
 * @returns whether a heading was found and focused
 */
export function focusHeading(container: ParentNode | null, selector = 'h2'): boolean {
  const heading = container?.querySelector<HTMLElement>(selector);
  if (heading === null || heading === undefined) {
    return false;
  }
  if (!heading.hasAttribute('tabindex')) {
    heading.tabIndex = -1;
  }
  heading.focus();
  return true;
}

/**
 * Whether focus can be moved without taking it from anybody: it is still on the control that
 * acted, or it was dropped to the body when that control went (#257, C2).
 *
 * @param control the control whose action is moving focus on; null when it is already gone
 * @returns true when moving focus would take it from nobody
 */
export function focusIsUnclaimed(control: Element | null): boolean {
  const active = document.activeElement;
  return active === null || active === document.body || active === control;
}
