import { screen } from '@testing-library/react';
import type { ByRoleOptions } from '@testing-library/react';

/**
 * The table row headed by the row header of this name, found through roles rather than markup.
 *
 * @param name the row header's accessible name, as `getByRole` takes it
 * @throws when no row header, or more than one, has that name
 */
export function rowHeadedBy(name: NonNullable<ByRoleOptions['name']>): HTMLElement {
  const header = screen.getByRole('rowheader', { name });
  const row = screen.getAllByRole('row').find((candidate) => candidate.contains(header));
  if (row === undefined) {
    throw new Error(`the row header ${header.textContent ?? ''} is in no row`);
  }
  return row;
}

/**
 * The row right after this one, in the order a screen reader's table mode reads them: where a
 * row's refusal belongs, so that one shown after another row, or after the last, is told apart.
 *
 * @throws when the row is not on the page, or is its last row
 */
export function rowAfter(row: HTMLElement): HTMLElement {
  const rows = screen.getAllByRole('row');
  const at = rows.indexOf(row);
  const next = at === -1 ? undefined : rows[at + 1];
  if (next === undefined) {
    throw new Error(at === -1 ? 'the row is not on the page' : 'no row follows it');
  }
  return next;
}
