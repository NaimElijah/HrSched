/**
 * How the rosters screen names a roster: its period, in local dates, as its row in the list shows
 * it. The list and the board both use it, so a roster is called the same in each.
 *
 * @param roster the roster's period, as ISO instants
 * @returns the period as "start to end", each in the browser's local date format
 */
export function labelOf(roster: { start: string; end: string }): string {
  return `${new Date(roster.start).toLocaleDateString()} to ${new Date(roster.end).toLocaleDateString()}`;
}
