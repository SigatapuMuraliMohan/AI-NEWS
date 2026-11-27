import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ScanLine, AlignLeft, Image as ImageIcon, Link2, ArrowUpRight, ArrowRight, LoaderCircle,
  ShieldCheck, FileSearch, Upload, CircleAlert, BookOpen, Check, Copy } from 'lucide-react';
import './style.css';

const tabs = [
  { id: 'text', label: 'Paste text', icon: AlignLeft },
  { id: 'image', label: 'Upload image', icon: ImageIcon },
  { id: 'url', label: 'Article link', icon: Link2 },
];

const stages = ['Extracting text and claims', 'Finding sources', 'Comparing claims'];
const labels = {
  loaded_language: 'Loaded language', one_sided_framing: 'One-sided framing', clickbait: 'Clickbait',
  headline_mismatch: 'Headline–body mismatch', exaggeration: 'Exaggeration', material_omission: 'Material omission',
};
const noEvidence = {
  unconfigured: 'Add a GNews API key to enable source search.',
  unavailable: 'The news search provider did not return usable sources. Check configuration or try later.',
  empty: 'The search found no usable passages for these claims.',
};
const example = {
  headline: 'Shocking discovery: the Moon is bigger than Earth',
  text: 'The Moon is larger than Earth and measures about 15,000 kilometers across, a claim repeated in a viral post. The post calls this a shocking discovery and says every textbook should be rewritten.',
};

async function request(url, options) {
  const response = await fetch(url, options);
  const data = await response.json().catch(() => null);
  if (!response.ok || !data) throw new Error(data?.message || 'The service could not respond. Please try again.');
  return data;
}

function App() {
  const [tab, setTab] = useState('text');
  const [text, setText] = useState('');
  const [headline, setHeadline] = useState('');
  const [url, setUrl] = useState('');
  const [file, setFile] = useState(null);
  const [id, setId] = useState(() => new URLSearchParams(location.search).get('analysis') || '');
  const [job, setJob] = useState(null);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const results = useRef(null);
  const upload = useRef(null);
  const busy = submitting || (id && (!job || ['Queued', 'Running'].includes(job.state)) && !error);
  const valid = tab === 'text' ? (text.match(/[\p{L}\p{N}]+/gu)?.length ?? 0) >= 4 :
    tab === 'url' ? /^https?:\/\//i.test(url.trim()) : Boolean(file);

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    let timer;
    let retries = 0;
    async function poll() {
      try {
        const next = await request('/api/analyses/' + encodeURIComponent(id), { signal: controller.signal });
        if (controller.signal.aborted) return;
        retries = 0;
        setJob(next);
        if (next.state === 'Queued' || next.state === 'Running') timer = setTimeout(poll, 1200);
      } catch (ex) {
        if (controller.signal.aborted) return;
        if (++retries < 3) timer = setTimeout(poll, 1600);
        else setError(ex.message);
      }
    }
    poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [id]);

  useEffect(() => {
    if (job?.state === 'Completed') results.current?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }, [job?.state]);

  function chooseFile(next) {
    if (next && (!['image/png', 'image/jpeg', 'image/webp'].includes(next.type) || next.size > 5 * 1024 * 1024)) {
      setError('Choose a PNG, JPEG, or WebP image up to 5 MB.');
      setFile(null);
      return;
    }
    setError('');
    setFile(next);
  }

  async function submit(event) {
    event.preventDefault();
    if (!valid || busy) return;
    setSubmitting(true);
    setError('');
    setId('');
    setJob(null);
    history.replaceState(null, '', location.pathname);
    const form = new FormData();
    form.set('type', tab);
    if (tab === 'text') { form.set('headline', headline); form.set('text', text); }
    if (tab === 'url') form.set('url', url.trim());
    if (tab === 'image') form.set('image', file);
    try {
      const next = await request('/api/analyses', { method: 'POST', body: form });
      history.replaceState(null, '', '?analysis=' + encodeURIComponent(next.id));
      setId(next.id);
      setJob(next);
    } catch (ex) { setError(ex.message); }
    finally { setSubmitting(false); }
  }

  return <>
    <header className="top"><div className="shell top-inner">
      <a className="brand" href="./"><span className="brand-icon"><ScanLine size={20}/></span> News Meter</a>
      <a className="top-link" href="#method">How it works <ArrowUpRight size={15}/></a>
    </div></header>
    <main className="shell">
      <section className="hero">
        <h1>AI News Bias &amp;<br/>Manipulation Risk Meter</h1>
        <p>Check what a news story claims, compare it with available evidence, and see which wording shapes the story.</p>
      </section>
      <div className="workbench">
        <section className="panel input-panel" aria-labelledby="submit-title">
          <div className="panel-heading"><h2 id="submit-title">Submit a news story</h2><p>Choose how you want to add the story.</p></div>
          <div className="tabs" aria-label="Submission method">
            {tabs.map(({ id: key, label, icon: Icon }) => <button type="button" key={key} aria-pressed={tab === key}
              onClick={() => { setTab(key); if (!id) setError(''); }}><Icon size={17}/>{label}</button>)}
          </div>
          <form onSubmit={submit}>
            <div className="fields">
              {tab === 'text' && <>
                <label htmlFor="headline">Headline <span>Optional</span></label>
                <input id="headline" value={headline} maxLength={300} onChange={event => setHeadline(event.target.value)} placeholder="Enter the article headline"/>
                <label htmlFor="article">Article text</label>
                <textarea id="article" required minLength={7} maxLength={20000} value={text} onChange={event => setText(event.target.value)} placeholder="Paste the news article or passage you’d like to check…"/>
                <div className="field-meta"><button type="button" className="text-button" onClick={() => { setHeadline(example.headline); setText(example.text); }}>Use sample text <ArrowUpRight size={14}/></button><span>{text.length.toLocaleString()} / 20,000</span></div>
              </>}
              {tab === 'image' && <>
                <label htmlFor="screenshot">News screenshot</label>
                <input className="sr-only" id="screenshot" type="file" ref={upload} accept="image/png,image/jpeg,image/webp" onChange={event => chooseFile(event.target.files[0])}/>
                <button type="button" className="dropzone" onClick={() => upload.current.click()} onDragOver={event => event.preventDefault()}
                  onDrop={event => { event.preventDefault(); chooseFile(event.dataTransfer.files[0]); }}>
                  <Upload size={24}/><strong>{file?.name || 'Choose a screenshot or drag it here'}</strong><span>PNG, JPEG, or WebP · Up to 5 MB</span>
                </button>
                <a className="text-button" href="/demo-screenshot.png" download>Download sample screenshot <ArrowUpRight size={14}/></a>
              </>}
              {tab === 'url' && <>
                <label htmlFor="url">Public article URL</label>
                <input id="url" required type="url" value={url} maxLength={2048} onChange={event => setUrl(event.target.value)} placeholder="https://publisher.com/article"/>
                <p className="help">If the article is paywalled or unavailable, use Paste text.</p>
                <button type="button" className="text-button" onClick={() => setUrl('https://science.nasa.gov/moon/facts/')}>Use sample link <ArrowUpRight size={14}/></button>
              </>}
            </div>
            <div className="form-footer"><span><ShieldCheck size={17}/> Results include source links when available.</span>
              <button className="primary" disabled={!valid || Boolean(busy)}>{busy ? 'Analyzing…' : 'Analyze story'} {busy ? <LoaderCircle className="spin" size={17}/> : <ArrowRight size={17}/>}</button>
            </div>
          </form>
        </section>
        <aside id="method" className="method panel">
          <div className="method-icon"><FileSearch size={22}/></div>
          <h2>How it works</h2>
          <ol>
            <li><span>1</span><div><strong>Extract the claims</strong><p>Identify up to three central factual statements.</p></div></li>
            <li><span>2</span><div><strong>Compare the evidence</strong><p>Search for source passages that address the same event.</p></div></li>
            <li><span>3</span><div><strong>Explain the scores</strong><p>See how the evidence and flagged language shaped your report.</p></div></li>
          </ol>
          <p className="method-note"><CircleAlert size={17}/> A limited search can leave some claims unverified.</p>
        </aside>
      </div>
      {(busy || error || job?.error) && <section className="panel status" aria-live="polite">
        {busy ? <><h2><LoaderCircle size={20} className="spin"/>{job?.stage || 'Queued'}</h2><div className="stage-list">
          {stages.map((stage, index) => <span key={stage} className={index <= stages.indexOf(job?.stage) ? 'active' : ''}>{stage}</span>)}
        </div></> : <p role="alert"><CircleAlert size={19}/>{error || job?.error}</p>}
      </section>}
      {job?.report && <Report report={job.report} target={results}/>}
    </main>
    <footer className="shell footer"><span>AI News Bias &amp; Manipulation Risk Meter</span><a href="#method">Analysis method <ArrowUpRight size={14}/></a></footer>
  </>;
}

function Highlight({ content, field, findings }) {
  let position = 0;
  const parts = [];
  for (const item of findings.filter(item => item.field === field).sort((a, b) => a.start - b.start)) {
    parts.push(content.slice(position, item.start));
    parts.push(<mark key={item.start} title={item.reason}>{content.slice(item.start, item.end)}</mark>);
    position = item.end;
  }
  parts.push(content.slice(position));
  return parts;
}

function RiskMeter({ score, level, thresholds, limited }) {
  const [mild, moderate, high] = thresholds;
  const band = score < mild ? 0 : score < moderate ? 1 : score < high ? 2 : 3;
  const edges = [0, mild, moderate, high, 100];
  const style = {
    '--position': score + '%',
    '--risk-color': 'var(--risk-' + band + ')',
    '--mild': mild + '%',
    '--moderate': moderate + '%',
    '--high': high + '%',
  };

  return <div className="risk-meter" style={style}>
    <div className="risk-reading">
      <div className="risk-ring" aria-hidden="true"><div><strong>{score}</strong><small> / 100</small></div></div>
      <div><p className="risk-level">{level}</p>{limited && <p className="limited">{limited}</p>}</div>
    </div>
    <div className="risk-scale" role="meter" aria-label="Manipulation risk"
      aria-valuemin={0} aria-valuemax={100} aria-valuenow={score}
      aria-valuetext={score + ' out of 100, ' + level} title={level + ' · ' + score + ' / 100'}>
      <div className="risk-track" aria-hidden="true"><span className="risk-pointer"/></div>
      <div className="risk-ticks" aria-hidden="true">
        {edges.map((edge, index) => <span key={index} style={{ left: edge + '%' }}>{edge}</span>)}
      </div>
    </div>
  </div>;
}

function Report({ report, target }) {
  const { article, claims, findings, sources, scores, credibility, searchStatus, limited } = report;
  const [copied, setCopied] = useState(false);
  const sourceById = Object.fromEntries(sources.map(source => [source.id, source]));
  const links = ids => ids.map(id => sourceById[id]).filter(Boolean).map(source =>
    <a key={source.id} className="citation" href={source.url} target="_blank" rel="noopener noreferrer">{source.publisher || source.title} <ArrowUpRight size={13}/></a>);
  const coverage = scores.total === 0 ? 'No checkable claims found' : `${scores.covered} of ${scores.total} claims had usable evidence`;
  async function copy() {
    try { await navigator.clipboard.writeText(location.href); setCopied(true); }
    catch { setCopied(false); }
  }
  return <section className="report" ref={target} aria-labelledby="report-title">
    <div className="report-header"><div><h2 id="report-title">Analysis report</h2>
      <p>{article.headline || 'Submitted story'}{article.publisher ? ` · ${article.publisher}` : ''}</p></div>
      <button className="secondary" onClick={copy}>{copied ? <Check size={16}/> : <Copy size={16}/>} {copied ? 'Copied' : 'Copy report link'}</button></div>
    <div className="score-grid">
      <div className="panel score"><h3>Manipulation risk meter</h3>
        <RiskMeter score={scores.risk} level={scores.level} thresholds={report.rubric.thresholds} limited={limited}/>
      </div>
      <div className="panel score"><h3>Bias score</h3><strong>{scores.bias}<small> / 100</small></strong>
        <meter min="0" max="100" value={scores.bias} aria-label="Bias score"/>
        <p>{findings.filter(f => f.biasPoints > 0).map(f => labels[f.category]).filter((label, i, all) => all.indexOf(label) === i).join(' · ') || 'No scoring wording or framing signals'}</p></div>
      <div className="panel score"><h3>Credibility score</h3>
        {credibility == null ? <strong className="empty-score">Insufficient evidence</strong> : <><strong>{credibility}<small> / 100</small></strong><meter min="0" max="100" value={credibility} aria-label="Credibility score"/></>}
        <p>{coverage}</p></div>
    </div>
    <p className="report-note">{claims.filter(c => c.verdict === 'Contradicted').length} contradicted {claims.filter(c => c.verdict === 'Contradicted').length === 1 ? 'claim' : 'claims'} · {findings.length} {findings.length === 1 ? 'flagged phrase' : 'flagged phrases'} · {coverage}.</p>
    {noEvidence[searchStatus] && <p className="report-note"><CircleAlert size={17}/>{noEvidence[searchStatus]}</p>}
    <section className="panel block"><h3>Key reasons and evidence</h3>
      {!claims.length && <p>No central factual claim was identified in this submission.</p>}
      {claims.map((claim, index) => <article className="claim" key={claim.claim}>
        <div className="claim-top"><span>Claim {index + 1}</span><strong>{claim.verdict}</strong>{claim.riskPoints > 0 && <span>+{claim.riskPoints} risk</span>}</div>
        <div className="compare"><div><h4>What you submitted</h4><p>{claim.claim}</p></div>
          <div><h4>What the evidence says</h4><p>{claim.explanation}</p><div className="links">{links(claim.evidenceIds)}</div></div></div>
      </article>)}
    </section>
    <section className="panel block"><h3>Highlighted words and phrases</h3>
      <p>Headline–body comparison: {report.headlineCheck}. {findings.length === 0 && 'No phrases met the rubric criteria.'}</p>
      <div className="article-preview">
        {article.headline && <h4><Highlight field="headline" content={article.headline} findings={findings}/></h4>}
        <div><Highlight field="text" content={article.text} findings={findings}/></div>
      </div>
      {findings.length > 0 && <ul className="findings">{findings.map(item => <li key={item.field + item.start}>
        <strong>“{item.excerpt}”</strong><p>{labels[item.category]} · {item.reason}</p>
        <span>+{item.riskPoints} risk{item.biasPoints > 0 && ` · +${item.biasPoints} bias`}</span><div className="links">{links(item.evidenceIds)}</div>
      </li>)}</ul>}
    </section>
    <section className="panel block"><h3>Source reliability</h3>
      <p>Submitted publisher: {article.url ? <a href={article.url} target="_blank" rel="noopener noreferrer">{article.publisher || 'Original article'}</a> : article.publisher || 'Unknown'}{article.date && ` · ${article.date.slice(0, 10)}`}</p>
      {!sources.length && <p>No source passages were cited.</p>}
      {sources.map(source => <article className="source" key={source.id}>
        <BookOpen size={19}/><div><p>{source.publisher || 'Unknown publisher'} · {source.type}{source.date && ` · ${source.date.slice(0, 10)}`}</p>
          <a href={source.url} target="_blank" rel="noopener noreferrer">{source.title} <ArrowUpRight size={15}/></a>
          <p>{source.why}</p><details><summary>Read retrieved passage</summary><p>{source.passage}</p></details>
        </div>
      </article>)}
    </section>
    <p className="report-note">Scoring rubric v{report.rubric.version}</p>
  </section>;
}

createRoot(document.getElementById('root')).render(<App/>);

