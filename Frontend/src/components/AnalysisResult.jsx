import { useState } from 'react';
import { DownloadSimple, Quotes } from '@phosphor-icons/react';

import { exportApi } from '../services/api';
import { useToast } from '../context/AppProviders';
import { AiContent, ConfidenceNotice } from './evidence';
import { Badge, Button, Select, cx } from './primitives';

const FORMATS = [
  { value: 'markdown', label: 'Markdown' },
  { value: 'pdf', label: 'PDF' },
  { value: 'bibtex', label: 'BibTeX' },
];

const TITLES = {
  summary: 'Paper summary',
  qa: 'Answer',
  comparison: 'Comparison',
  research_gap: 'Potential research gaps',
  novelty: 'Novelty assessment',
  literature_review: 'Literature review draft',
  concepts: 'Extracted concepts',
};

function section(value) {
  return value ? value.replace(/_/g, ' ') : 'unlabelled';
}

/* -------------------------------------------------------------------------- */

export function ExportControls({ analysisId }) {
  const toast = useToast();
  const [format, setFormat] = useState('markdown');
  const [busy, setBusy] = useState(false);

  if (!analysisId) return null;

  const download = async () => {
    setBusy(true);
    try {
      const { blob, filename } = await exportApi.download(analysisId, format);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex items-center gap-2">
      <Select
        aria-label="Export format"
        value={format}
        onChange={(event) => setFormat(event.target.value)}
        className="h-8 w-auto py-0 text-[0.8125rem]"
      >
        {FORMATS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </Select>
      <Button size="sm" icon={DownloadSimple} loading={busy} onClick={download}>
        Export
      </Button>
    </div>
  );
}

/* -------------------------------------------------------------------------- */

function Bullets({ heading, items }) {
  if (!items?.length) return null;
  return (
    <section className="mt-5 first:mt-0">
      <h4 className="text-[0.8125rem] font-semibold text-[var(--text)]">{heading}</h4>
      <ul className="mt-2 flex flex-col gap-1.5">
        {items.map((item, index) => (
          <li key={index} className="flex gap-2 text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
            <span aria-hidden className="mt-[0.55em] h-1 w-1 shrink-0 rounded-full bg-[var(--text-subtle)]" />
            <span>{typeof item === 'string' ? item : item.statement || item.text || ''}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Prose({ heading, text }) {
  if (!text) return null;
  return (
    <section className="mt-5 first:mt-0">
      <h4 className="text-[0.8125rem] font-semibold text-[var(--text)]">{heading}</h4>
      <p className="prose-answer mt-1.5 text-[0.875rem] text-[var(--text-muted)]">{text}</p>
    </section>
  );
}

function EvidenceQuote({ item }) {
  return (
    <figure className="rounded-[var(--radius-control)] border border-[var(--evidence-border)] bg-[var(--evidence-surface)] px-3.5 py-3">
      <blockquote className="text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
        {item.quote}
      </blockquote>
      <figcaption className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1">
        <Quotes size={11} weight="fill" className="text-[var(--text-subtle)]" aria-hidden />
        <span className="text-xs font-medium text-[var(--text)]">
          {item.paper_title || 'Cited paper'}
        </span>
        <span className="label-mono">{section(item.section)}</span>
      </figcaption>
    </figure>
  );
}

/* -------------------------------------------------------------------------- */

function SummaryBody({ result }) {
  return (
    <>
      <Prose heading="Research problem" text={result.research_problem} />
      <Prose heading="Approach" text={result.approach} />
      <Bullets heading="Key findings" items={result.key_findings} />
      <Bullets heading="Limitations reported by the authors" items={result.limitations} />
      {result.takeaway && (
        <p className="mt-5 border-l-2 border-[var(--ai-border)] pl-3 text-[0.9375rem] font-medium leading-relaxed text-[var(--text)]">
          {result.takeaway}
        </p>
      )}
    </>
  );
}

function QaBody({ result, paperTitles = {} }) {
  return (
    <>
      <p className="prose-answer text-[0.9375rem] text-[var(--text)]">{result.answer}</p>
      {result.claims?.length > 0 && (
        <section className="mt-5">
          <h4 className="text-[0.8125rem] font-semibold text-[var(--text)]">
            Supporting evidence
          </h4>
          <ul className="mt-2 flex flex-col gap-2.5">
            {result.claims.map((claim, index) => (
              <li
                key={claim.chunk_id ?? index}
                className="rounded-[var(--radius-control)] border border-[var(--evidence-border)] bg-[var(--evidence-surface)] px-3.5 py-3"
              >
                <p className="text-[0.875rem] leading-relaxed text-[var(--text)]">
                  {claim.statement}
                </p>
                <p className="mt-1.5 flex flex-wrap items-center gap-x-2 text-xs text-[var(--text-subtle)]">
                  <span className="font-medium text-[var(--text-muted)]">
                    {paperTitles[claim.paper_id] || 'Cited paper'}
                  </span>
                  <span className="label-mono">{section(claim.section)}</span>
                </p>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

function ComparisonBody({ result }) {
  const papers = result.papers ?? [];
  const dimensions = result.dimensions ?? [];
  if (!papers.length || !dimensions.length) return null;

  return (
    // The table scrolls inside its own container so the page never scrolls
    // sideways on a narrow screen.
    <div className="-mx-4 overflow-x-auto px-4">
      <table className="w-full min-w-[42rem] border-collapse text-left">
        <thead>
          <tr>
            <th scope="col" className="w-40 border-b border-[var(--ai-border)] pb-2 pr-4 text-[0.8125rem] font-semibold text-[var(--text)]">
              Dimension
            </th>
            {papers.map((paper) => (
              <th
                key={paper.paper_id}
                scope="col"
                className="border-b border-[var(--ai-border)] px-3 pb-2 text-[0.8125rem] font-semibold text-[var(--text)]"
              >
                {paper.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {dimensions.map((dimension) => (
            <tr key={dimension.name} className="align-top">
              <th
                scope="row"
                className="border-b border-[var(--ai-border)]/60 py-3 pr-4 text-[0.8125rem] font-medium text-[var(--text-muted)]"
              >
                {dimension.name}
              </th>
              {papers.map((paper, index) => {
                const value = dimension.values?.[index] ?? 'Not reported';
                const absent = value === 'Not reported';
                return (
                  <td
                    key={paper.paper_id}
                    className={cx(
                      'border-b border-[var(--ai-border)]/60 px-3 py-3 text-[0.8125rem] leading-relaxed',
                      // "Not reported" is a real finding, shown as absence
                      // rather than dressed up as an answer.
                      absent ? 'italic text-[var(--text-subtle)]' : 'text-[var(--text)]',
                    )}
                  >
                    {value}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ResearchGapBody({ result }) {
  if (!result.gaps?.length) {
    return (
      <p className="text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
        No gap could be supported by evidence in this corpus. A gap without a citation behind it is
        discarded rather than shown, so an empty result here means the papers did not support one.
      </p>
    );
  }
  return (
    <ol className="flex flex-col gap-5">
      {result.gaps.map((gap, index) => (
        <li key={index} className="border-t border-[var(--ai-border)] pt-4 first:border-t-0 first:pt-0">
          <div className="flex flex-wrap items-start justify-between gap-2">
            {/* Always labelled as potential, never as a finding. */}
            <Badge tone="ai">Potential research gap</Badge>
            {gap.confidence && <Badge tone="neutral">Confidence: {gap.confidence}</Badge>}
          </div>
          <p className="mt-2.5 text-[0.9375rem] font-medium leading-relaxed text-[var(--text)]">
            {gap.statement}
          </p>
          {gap.why_underexplored && (
            <p className="mt-2 text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
              {gap.why_underexplored}
            </p>
          )}
          {gap.evidence?.length > 0 && (
            <div className="mt-3 flex flex-col gap-2">
              {gap.evidence.map((item, position) => (
                <EvidenceQuote key={position} item={item} />
              ))}
            </div>
          )}
        </li>
      ))}
    </ol>
  );
}

function NoveltyBody({ result, verification }) {
  return (
    <>
      {result.similar_work?.length > 0 && (
        <section>
          <h4 className="text-[0.8125rem] font-semibold text-[var(--text)]">
            Similar existing work
          </h4>
          <ul className="mt-2 flex flex-col gap-2">
            {result.similar_work.map((work, index) => (
              <li
                key={index}
                className="rounded-[var(--radius-control)] border border-[var(--evidence-border)] bg-[var(--evidence-surface)] px-3.5 py-3"
              >
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <p className="text-[0.875rem] font-medium text-[var(--text)]">{work.title}</p>
                  {typeof work.similarity === 'number' && (
                    <span className="label-mono tabular-nums">
                      similarity {work.similarity.toFixed(2)}
                    </span>
                  )}
                </div>
                {work.why_similar && (
                  <p className="mt-1 text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
                    {work.why_similar}
                  </p>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      <Bullets heading="Areas of overlap" items={result.overlap} />
      <Bullets heading="Existing methodologies" items={result.existing_methodologies} />
      <Bullets heading="Differences" items={result.differences} />
      <Bullets heading="Potentially novel aspects" items={result.potentially_novel} />

      {verification?.enabled && (
        <section className="mt-5 rounded-[var(--radius-control)] border border-[var(--border)] bg-[var(--surface-sunken)] px-3.5 py-3">
          <h4 className="text-[0.8125rem] font-semibold text-[var(--text)]">
            Second opinion from a different model
          </h4>
          {verification.second_opinion_available ? (
            <>
              <Bullets heading="Both models agree" items={verification.agreements} />
              {/* Disagreements are shown, never averaged away. */}
              <Bullets heading="The models disagree" items={verification.disagreements} />
            </>
          ) : (
            <p className="mt-1.5 text-[0.8125rem] text-[var(--text-muted)]">
              A second opinion was requested but could not be obtained, so only one assessment is
              shown.
            </p>
          )}
        </section>
      )}
    </>
  );
}

function ReviewBody({ result }) {
  return (
    <>
      <Prose heading="Overview" text={result.overview} />
      {result.themes?.map((theme, index) => (
        <section key={index} className="mt-5">
          <h4 className="text-[0.8125rem] font-semibold text-[var(--text)]">{theme.name}</h4>
          <p className="mt-1.5 text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
            {theme.description}
          </p>
        </section>
      ))}
      <Bullets heading="Methodological approaches" items={result.methodologies} />
      <Bullets heading="Datasets and evaluation" items={result.datasets} />
      <Bullets heading="Points of agreement" items={result.agreements} />
      <Bullets heading="Contradictions between papers" items={result.contradictions} />
      <Bullets heading="Reported limitations" items={result.limitations} />
      {result.references?.length > 0 && (
        <section className="mt-5">
          <h4 className="text-[0.8125rem] font-semibold text-[var(--text)]">
            Papers actually cited
          </h4>
          <ol className="mt-2 flex flex-col gap-1">
            {result.references.map((reference, index) => (
              <li key={index} className="text-[0.8125rem] text-[var(--text-muted)]">
                {index + 1}. {reference.title}
                {reference.year ? ` (${reference.year})` : ''}
              </li>
            ))}
          </ol>
        </section>
      )}
    </>
  );
}

function ConceptsBody({ result }) {
  if (!result.concepts?.length) return null;
  return (
    <ul className="flex flex-wrap gap-1.5">
      {result.concepts.map((concept, index) => (
        <li key={index}>
          <Badge tone={concept.concept_type === 'method' ? 'accent' : 'neutral'}>
            {concept.concept}
          </Badge>
        </li>
      ))}
    </ul>
  );
}

/* -------------------------------------------------------------------------- */

/**
 * Renders a stored analysis.
 *
 * Everything model-generated sits inside the AI surface, and the confidence and
 * limitations always render with it rather than being an optional extra.
 */
export function AnalysisResult({ analysis, paperTitles, cached, children }) {
  if (!analysis) return null;

  const result = analysis.result ?? {};
  const corpusSize = analysis.evidenceRefs?.corpus_size;
  const type = analysis.analysisType;

  return (
    <AiContent
      title={TITLES[type] ?? 'AI generated'}
      actions={
        <div className="flex items-center gap-2">
          {(cached ?? analysis.fromCache) && (
            <Badge tone="neutral">Reused earlier result</Badge>
          )}
          <ExportControls analysisId={analysis.id} />
        </div>
      }
    >
      {type === 'summary' && <SummaryBody result={result} />}
      {type === 'qa' && <QaBody result={result} paperTitles={paperTitles} />}
      {type === 'comparison' && <ComparisonBody result={result} />}
      {type === 'research_gap' && <ResearchGapBody result={result} />}
      {type === 'novelty' && <NoveltyBody result={result} verification={analysis.verification} />}
      {type === 'literature_review' && <ReviewBody result={result} />}
      {type === 'concepts' && <ConceptsBody result={result} />}

      <div className="mt-5">
        <ConfidenceNotice
          confidence={analysis.confidence}
          corpusSize={corpusSize}
          limitations={typeof result.limitations === 'string' ? result.limitations : undefined}
          kind={type}
        />
      </div>
      {children}
    </AiContent>
  );
}
