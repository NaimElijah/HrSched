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
