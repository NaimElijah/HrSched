import { Failure } from './Feedback';

/**
 * The refusal of one row's change, in a table row of its own right after that row (#257, C6).
 *
 * Its cell spans every column and names the refused row's header, so a screen reader's table mode
 * says whose refusal it is. The message sits in a `.row-refusal` box, which the stylesheet pins to
 * the visible part of a scrolled table: a cell as wide as the table would otherwise start off
 * screen from the button that was pressed.
 *
 * @param error what refused the change, rendered as {@link Failure} renders it
 * @param id the cell's id, so the control the refusal is about can be described by it
 * @param headers the id of the refused row's header cell
 * @param columns how many columns the table has, all of which the cell spans
 */
export function RowRefusal({
  error,
  id,
  headers,
  columns,
}: {
  error: unknown;
  id: string;
  headers: string;
  columns: number;
}) {
  return (
    <tr>
      <td colSpan={columns} id={id} headers={headers}>
        <div className="row-refusal">
          <Failure error={error} />
        </div>
      </td>
    </tr>
  );
}
