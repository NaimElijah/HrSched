import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import { workforce } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';

/** Scheduler: what needs covering, over an explicit span. */
export function ShiftsPage({ span }: { span: { from: string; to: string } }) {
  const sessionKey = useSessionKey();
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [headcount, setHeadcount] = useState('1');
  const [required, setRequired] = useState<string[]>([]);
  const [unpopular, setUnpopular] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);

  const shifts = useAsync(
    () => scheduling.findShifts(span.from, span.to),
    `${sessionKey}|${span.from}|${span.to}`,
  );
  const qualifications = useAsync(() => workforce.findQualifications(), sessionKey);
  const qualificationNames = new Map(
    (qualifications.value ?? []).map((qualification) => [qualification.id, qualification.name]),
  );

  async function define(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    const from = new Date(start);
    const to = new Date(end);
    if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
      setFailure(new Error('Give both a start and an end.'));
      return;
    }
    try {
      await scheduling.defineShift({
        start: from.toISOString(),
        end: to.toISOString(),
        // The zone `new Date` just read the inputs in, so the one these times mean. The backend
        // counts the shift's working-time days in it (R36).
        zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        requiredHeadcount: Number(headcount),
        requiredQualifications: required,
        unpopular,
      });
      shifts.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  return (
    <section>
      <h2>Shifts</h2>
      <p className="hint">
        A shift may only require qualifications this organisation has defined. Requiring one it has
        not is refused, because nobody could ever hold it and the shift would be silently
        uncoverable forever.
      </p>
      <p className="hint">
        Mark a shift unpopular when it is work people would rather not do. The draft check counts
        how many of them the most burdened person carries; the mark changes no rule a roster must
        obey.
      </p>

      <form onSubmit={define} className="grid">
        <label>
          Starts
          <input
            type="datetime-local"
            value={start}
            onChange={(event) => setStart(event.target.value)}
            required
          />
        </label>
        <label>
          Ends
          <input
            type="datetime-local"
            value={end}
            onChange={(event) => setEnd(event.target.value)}
            required
          />
        </label>
        <label>
          People needed
          <input
            type="number"
            min="1"
            value={headcount}
            onChange={(event) => setHeadcount(event.target.value)}
            required
          />
        </label>
        <label>
          Required qualifications
          <select
            multiple
            value={required}
            onChange={(event) =>
              setRequired(Array.from(event.target.selectedOptions, (option) => option.value))
            }
          >
            {(qualifications.value ?? []).map((qualification) => (
              <option key={qualification.id} value={qualification.id}>
                {qualification.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input
            type="checkbox"
            checked={unpopular}
            onChange={(event) => setUnpopular(event.target.checked)}
          />
          Unpopular
        </label>
        <button type="submit">Define shift</button>
      </form>

      <Failure error={failure} />
      <Failure error={shifts.error} />
      <Busy busy={shifts.busy} />

      <table>
        <thead>
          <tr>
            <th>Starts</th>
            <th>Ends</th>
            <th>Needs</th>
            <th>Requires</th>
            <th>Unpopular</th>
          </tr>
        </thead>
        <tbody>
          {(shifts.value ?? []).map((shift) => (
            <tr key={shift.id}>
              <td>{new Date(shift.start).toLocaleString()}</td>
              <td>{new Date(shift.end).toLocaleString()}</td>
              <td>{shift.requiredHeadcount}</td>
              <td>
                {shift.requiredQualifications.length === 0
                  ? '—'
                  : shift.requiredQualifications
                      .map((id) => qualificationNames.get(id) ?? id)
                      .join(', ')}
              </td>
              <td>{shift.unpopular ? 'Unpopular' : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
