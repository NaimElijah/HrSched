import type { ObjectiveScore } from '../api/scheduling';
import { OBJECTIVE_FIELDS } from '../components/objectiveFields';

/**
 * How a draft measures on each objective, and the weight the organisation puts on it (F34,
 * ADR-010).
 *
 * Shown beside the rules and never in their place: a score ranks rosters and decides nothing, so a
 * draft breaking a rule is refused however well it scores. Each count is the backend's; nothing is
 * worked out here.
 */
export function ObjectiveScores({
  objectives,
  weightedTotal,
}: {
  objectives: readonly ObjectiveScore[];
  weightedTotal: number;
}) {
  const weighed = objectives.some((scored) => scored.weight !== null);
  return (
    <>
      <table>
        <caption>Against the objectives, lower being better</caption>
        <thead>
          <tr>
            <th>Objective</th>
            <th>Count</th>
            <th>Weight</th>
          </tr>
        </thead>
        <tbody>
          {OBJECTIVE_FIELDS.map(([key, name, measures]) => {
            const scored = objectives.find((each) => each.objective === key.toUpperCase());
            return (
              <tr key={key}>
                <td>{`${name}: ${measures}`}</td>
                <td>{scored === undefined ? '—' : scored.measure}</td>
                <td>{scored?.weight ?? 'not weighed'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p>
        {weighed
          ? `Weighted total: ${String(weightedTotal)}.`
          : 'No objective is weighed, so every draft totals zero.'}
      </p>
    </>
  );
}
