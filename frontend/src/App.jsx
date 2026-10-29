import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import './actions.css'

const DASH = '\u2014'
const PAGE_SIZE = 25
const STATUSES = ['PENDING', 'RUNNING', 'SUCCEEDED', 'DEAD', 'CANCELLED']
const CRON_PRESETS = [
  { label: 'Every minute', value: '* * * * *' },
  { label: 'Every 5 minutes', value: '*/5 * * * *' },
  { label: 'Hourly', value: '0 * * * *' },
  { label: 'Daily 09:00', value: '0 9 * * *' },
  { label: 'Weekdays 09:00', value: '0 9 * * 1-5' },
]

async function errorMessage(response) {
  let message = `Request failed (${response.status})`
  try {
    const body = await response.json()
    if (body.errors) {
      const details = Object.entries(body.errors).map(([field, text]) => `${field}: ${text}`).join('; ')
      message = details ? `${body.detail || 'Validation failed'} - ${details}` : message
    } else {
      message = body.detail || body.message || body.error || message
    }
  } catch {
    // Keep the HTTP status message when the response is not JSON.
  }
  return message
}

async function request(url, options) {
  const response = await fetch(url, options)
  if (!response.ok) throw new Error(await errorMessage(response))
  return response.json()
}

// Job lists come back as a plain array; the total number of matches is in the X-Total-Count header.
async function requestPage(url) {
  const response = await fetch(url)
  if (!response.ok) throw new Error(await errorMessage(response))
  const items = await response.json()
  const header = response.headers.get('X-Total-Count')
  return { items, total: header === null ? items.length : Number(header) }
}

// Like request(), but also reports whether the server answered with an earlier job (same Idempotency-Key).
async function submitJobRequest(url, options) {
  const response = await fetch(url, options)
  if (!response.ok) throw new Error(await errorMessage(response))
  return { job: await response.json(), replayed: response.headers.get('Idempotent-Replayed') === 'true' }
}

function newKey() {
  return window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID() : `key-${Date.now()}`
}

function browserZone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

function formatDate(value) {
  if (!value) return DASH
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? DASH : date.toLocaleString()
}

function relativeTime(value) {
  if (!value) return DASH
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return DASH
  const seconds = Math.round((date.getTime() - Date.now()) / 1000)
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
  if (Math.abs(seconds) < 60) return formatter.format(seconds, 'second')
  if (Math.abs(seconds) < 3600) return formatter.format(Math.round(seconds / 60), 'minute')
  if (Math.abs(seconds) < 86400) return formatter.format(Math.round(seconds / 3600), 'hour')
  return formatter.format(Math.round(seconds / 86400), 'day')
}

function formatWait(seconds) {
  if (seconds === null || seconds === undefined) return 'nothing waiting'
  if (seconds < 60) return `${Math.max(0, Math.round(seconds))}s`
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`
  return `${(seconds / 3600).toFixed(1)} h`
}

function shortId(id) {
  return id ? id.slice(0, 8) : DASH
}

function StatusBadge({ status }) {
  return <span className={`status-badge status-${(status || 'unknown').toLowerCase()}`}>{status || 'UNKNOWN'}</span>
}

function Icon({ name, size = 18 }) {
  const shared = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round',
    strokeLinejoin: 'round',
    'aria-hidden': true,
  }
  const paths = {
    grid: <><rect x="3.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="3.5" y="13.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="13.5" width="7" height="7" rx="1.5" /></>,
    layers: <><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="m3 12 9 5 9-5" /><path d="m3 16 9 5 9-5" /></>,
    search: <><circle cx="10.8" cy="10.8" r="6.8" /><path d="m16 16 4.5 4.5" /></>,
    plus: <><path d="M12 5v14" /><path d="M5 12h14" /></>,
    refresh: <><path d="M20 7v5h-5" /><path d="M4.9 9a7.5 7.5 0 0 1 12.7-2L20 12" /><path d="M4 17v-5h5" /><path d="M19.1 15a7.5 7.5 0 0 1-12.7 2L4 12" /></>,
    close: <><path d="m18 6-12 12" /><path d="m6 6 12 12" /></>,
    chevron: <path d="m9 18 6-6-6-6" />,
    chevronDown: <path d="m6 9 6 6 6-6" />,
    key: <><circle cx="8" cy="15" r="4" /><path d="m10.8 12.2 8.7-8.7" /><path d="m16 7 3 3" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    calendar: <><rect x="3.5" y="5" width="17" height="15.5" rx="2" /><path d="M3.5 10h17" /><path d="M8 3v4" /><path d="M16 3v4" /></>,
    check: <><path d="m5 12 4 4L19 6" /></>,
    activity: <><path d="M3 12h4l3-8 4 16 3-8h4" /></>,
    alert: <><path d="M10.3 3.9 2.7 17a2 2 0 0 0 1.7 3h15.2a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" /><path d="M12 9v4" /><path d="M12 17h.01" /></>,
    sliders: <><path d="M4 21v-7" /><path d="M4 10V3" /><path d="M12 21v-9" /><path d="M12 8V3" /><path d="M20 21v-5" /><path d="M20 12V3" /><path d="M2 14h4" /><path d="M10 8h4" /><path d="M18 16h4" /></>,
  }
  return <svg {...shared}>{paths[name]}</svg>
}

function MetricCard({ label, count, icon, tone, loading }) {
  return (
    <div className="metric-card">
      <div className={`metric-icon ${tone}`}><Icon name={icon} size={19} /></div>
      <div className="metric-copy">
        <span>{label}</span>
        <strong>{loading ? DASH : count}</strong>
      </div>
      <span className="metric-caption">all jobs</span>
    </div>
  )
}

function JobTable({ jobs, loading, onSelect, onClear }) {
  if (loading && jobs.length === 0) {
    return <div className="table-message"><span className="spinner" /> Loading jobs...</div>
  }
  if (!loading && jobs.length === 0) {
    return (
      <div className="empty-state">
        <div className="empty-icon"><Icon name="layers" size={25} /></div>
        <strong>No jobs found</strong>
        <p>Try another filter or submit a new job to get started.</p>
        <button className="text-button" onClick={onClear}>Clear filters</button>
      </div>
    )
  }
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Job</th>
            <th>Status</th>
            <th>Priority</th>
            <th>Attempts</th>
            <th>Created</th>
            <th aria-label="Open job" />
          </tr>
        </thead>
        <tbody>
          {jobs.map((job) => (
            <tr key={job.id} onClick={() => onSelect(job)} tabIndex={0} onKeyDown={(event) => {
              if (event.key === 'Enter') onSelect(job)
            }}>
              <td>
                <div className="job-cell">
                  <span className="job-type">{job.type}</span>
                  <span className="job-id">#{shortId(job.id)}{job.scheduleId ? ' - scheduled' : ''}</span>
                </div>
              </td>
              <td><StatusBadge status={job.status} /></td>
              <td><span className="priority"><span className={`priority-dot priority-${job.priority >= 8 ? 'high' : job.priority >= 5 ? 'normal' : 'low'}`} />{job.priority ?? DASH}</span></td>
              <td><span className="attempt-count">{job.attempts ?? 0}<span> / {job.maxAttempts ?? DASH}</span></span></td>
              <td><span className="created-time">{relativeTime(job.createdAt)}</span></td>
              <td><button className="row-open" aria-label={`View job ${shortId(job.id)}`}><Icon name="chevron" size={17} /></button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SubmitModal({ onClose, onSubmitted }) {
  const [idempotencyKey, setIdempotencyKey] = useState('')
  const [type, setType] = useState('SEND_EMAIL')
  const [payload, setPayload] = useState('{\n  "to": "user@example.com"\n}')
  const [priority, setPriority] = useState('5')
  const [delaySeconds, setDelaySeconds] = useState('0')
  const [maxAttempts, setMaxAttempts] = useState('5')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  function changeType(value) {
    setType(value)
    if (value === 'SLOW') setPayload('{\n  "seconds": 20\n}')
    else if (value === 'SEND_EMAIL') setPayload('{\n  "to": "user@example.com"\n}')
    else setPayload('{}')
  }

  async function submit(event) {
    event.preventDefault()
    setError('')
    let parsedPayload
    try {
      parsedPayload = JSON.parse(payload)
    } catch {
      setError('Payload must be valid JSON.')
      return
    }
    if (parsedPayload === null) {
      setError('Payload cannot be null.')
      return
    }

    setSubmitting(true)
    try {
      const key = idempotencyKey.trim()
      const { job, replayed } = await submitJobRequest('/api/jobs', {
        method: 'POST',
        headers: key ? { 'Content-Type': 'application/json', 'Idempotency-Key': key } : { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          type: type.trim(),
          payload: parsedPayload,
          priority: Number(priority),
          delaySeconds: Number(delaySeconds),
          maxAttempts: Number(maxAttempts),
        }),
      })
      onSubmitted(job, replayed)
    } catch (submissionError) {
      setError(submissionError.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="submit-title">
        <div className="modal-header">
          <div><span className="eyebrow">QUEUE ACTION</span><h2 id="submit-title">Create a job</h2></div>
          <button className="icon-button" onClick={onClose} aria-label="Close dialog"><Icon name="close" /></button>
        </div>
        <p className="modal-intro">Add a task to the queue. Your worker will pick it up when it's ready.</p>
        <form onSubmit={submit}>
          <label className="field-label" htmlFor="job-type">Job type</label>
          <input id="job-type" className="input-control" value={type} onChange={(event) => setType(event.target.value)} required maxLength={100} placeholder="e.g. SEND_EMAIL" />
          <div className="type-suggestions">
            {['SEND_EMAIL', 'GENERATE_REPORT', 'NOOP', 'SLOW', 'ALWAYS_FAIL'].map((suggestion) => (
              <button key={suggestion} type="button" onClick={() => changeType(suggestion)} className={type === suggestion ? 'suggestion active' : 'suggestion'}>{suggestion}</button>
            ))}
          </div>

          <label className="field-label payload-label" htmlFor="job-payload">Payload <span>JSON</span></label>
          <textarea id="job-payload" className="input-control code-input" value={payload} onChange={(event) => setPayload(event.target.value)} rows={5} spellCheck={false} />

          <div className="form-grid">
            <div><label className="field-label" htmlFor="job-priority">Priority <span>1-10</span></label><input id="job-priority" className="input-control" type="number" min="1" max="10" value={priority} onChange={(event) => setPriority(event.target.value)} required /></div>
            <div><label className="field-label" htmlFor="job-max-attempts">Max attempts <span>1-20</span></label><input id="job-max-attempts" className="input-control" type="number" min="1" max="20" value={maxAttempts} onChange={(event) => setMaxAttempts(event.target.value)} required /></div>
          </div>
          <label className="field-label delay-label" htmlFor="job-delay">Delay <span>seconds before the job can run</span></label>
          <input id="job-delay" className="input-control" type="number" min="0" value={delaySeconds} onChange={(event) => setDelaySeconds(event.target.value)} required />

          <label className="field-label delay-label" htmlFor="job-key">Idempotency key <span>optional - same key and same job = no duplicate</span></label>
          <input id="job-key" className="input-control" value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} maxLength={200} spellCheck={false} placeholder="e.g. invoice-1042-email" />
          <div className="type-suggestions">
            <button type="button" className="suggestion" onClick={() => setIdempotencyKey(newKey())}>Generate random key</button>
          </div>

          {error && <div className="form-error" role="alert"><Icon name="alert" size={16} />{error}</div>}
          <div className="modal-actions">
            <button type="button" className="button button-secondary" onClick={onClose}>Cancel</button>
            <button type="submit" className="button button-primary" disabled={submitting}>{submitting ? <><span className="spinner spinner-white" />Adding job...</> : <><Icon name="plus" size={17} />Add to queue</>}</button>
          </div>
        </form>
      </section>
    </div>
  )
}

function ScheduleModal({ onClose, onCreated }) {
  const [name, setName] = useState('')
  const [type, setType] = useState('SEND_EMAIL')
  const [cron, setCron] = useState('*/5 * * * *')
  const [timezone, setTimezone] = useState(browserZone())
  const [payload, setPayload] = useState('{\n  "to": "user@example.com"\n}')
  const [priority, setPriority] = useState('5')
  const [maxAttempts, setMaxAttempts] = useState('5')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  function changeType(value) {
    setType(value)
    if (value === 'SLOW') setPayload('{\n  "seconds": 20\n}')
    else if (value === 'SEND_EMAIL') setPayload('{\n  "to": "user@example.com"\n}')
    else setPayload('{}')
  }

  async function submit(event) {
    event.preventDefault()
    setError('')
    let parsedPayload
    try {
      parsedPayload = JSON.parse(payload)
    } catch {
      setError('Payload must be valid JSON.')
      return
    }
    if (parsedPayload === null) {
      setError('Payload cannot be null.')
      return
    }

    setSubmitting(true)
    try {
      const schedule = await request('/api/schedules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.trim(),
          type: type.trim(),
          payload: parsedPayload,
          cron: cron.trim(),
          timezone: timezone.trim() || 'UTC',
          priority: Number(priority),
          maxAttempts: Number(maxAttempts),
        }),
      })
      onCreated(schedule)
    } catch (submissionError) {
      setError(submissionError.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="schedule-title">
        <div className="modal-header">
          <div><span className="eyebrow">AUTOMATION</span><h2 id="schedule-title">New recurring job</h2></div>
          <button className="icon-button" onClick={onClose} aria-label="Close dialog"><Icon name="close" /></button>
        </div>
        <p className="modal-intro">A job is created automatically every time the schedule fires.</p>
        <form onSubmit={submit}>
          <label className="field-label" htmlFor="schedule-name">Name <span>must be unique</span></label>
          <input id="schedule-name" className="input-control" value={name} onChange={(event) => setName(event.target.value)} required maxLength={100} placeholder="e.g. daily-report" />

          <label className="field-label delay-label" htmlFor="schedule-type">Job type</label>
          <input id="schedule-type" className="input-control" value={type} onChange={(event) => setType(event.target.value)} required maxLength={100} />
          <div className="type-suggestions">
            {['SEND_EMAIL', 'GENERATE_REPORT', 'NOOP', 'SLOW', 'ALWAYS_FAIL'].map((suggestion) => (
              <button key={suggestion} type="button" onClick={() => changeType(suggestion)} className={type === suggestion ? 'suggestion active' : 'suggestion'}>{suggestion}</button>
            ))}
          </div>

          <label className="field-label payload-label" htmlFor="schedule-cron">Cron <span>minute hour day month weekday</span></label>
          <input id="schedule-cron" className="input-control code-input" value={cron} onChange={(event) => setCron(event.target.value)} required maxLength={120} spellCheck={false} />
          <div className="type-suggestions">
            {CRON_PRESETS.map((preset) => (
              <button key={preset.value} type="button" onClick={() => setCron(preset.value)} className={cron === preset.value ? 'suggestion active' : 'suggestion'}>{preset.label}</button>
            ))}
          </div>

          <label className="field-label delay-label" htmlFor="schedule-timezone">Time zone <span>e.g. Asia/Kolkata, UTC</span></label>
          <input id="schedule-timezone" className="input-control" value={timezone} onChange={(event) => setTimezone(event.target.value)} maxLength={50} />

          <label className="field-label delay-label" htmlFor="schedule-payload">Payload <span>JSON</span></label>
          <textarea id="schedule-payload" className="input-control code-input" value={payload} onChange={(event) => setPayload(event.target.value)} rows={4} spellCheck={false} />

          <div className="form-grid">
            <div><label className="field-label" htmlFor="schedule-priority">Priority <span>1-10</span></label><input id="schedule-priority" className="input-control" type="number" min="1" max="10" value={priority} onChange={(event) => setPriority(event.target.value)} required /></div>
            <div><label className="field-label" htmlFor="schedule-attempts">Max attempts <span>1-20</span></label><input id="schedule-attempts" className="input-control" type="number" min="1" max="20" value={maxAttempts} onChange={(event) => setMaxAttempts(event.target.value)} required /></div>
          </div>

          {error && <div className="form-error" role="alert"><Icon name="alert" size={16} />{error}</div>}
          <div className="modal-actions">
            <button type="button" className="button button-secondary" onClick={onClose}>Cancel</button>
            <button type="submit" className="button button-primary" disabled={submitting}>{submitting ? <><span className="spinner spinner-white" />Saving...</> : <><Icon name="calendar" size={17} />Create schedule</>}</button>
          </div>
        </form>
      </section>
    </div>
  )
}

function resultSummary(run) {
  if (run.result != null) {
    const text = JSON.stringify(run.result)
    return text.length > 90 ? `${text.slice(0, 90)}...` : text
  }
  if (run.lastError) return run.lastError.length > 90 ? `${run.lastError.slice(0, 90)}...` : run.lastError
  return DASH
}

function SchedulesPage({ schedules, loading, error, message, onCreate, onAction, onOpenJob }) {
  const [openId, setOpenId] = useState(null)
  const [runs, setRuns] = useState([])
  const [runsLoading, setRunsLoading] = useState(false)
  const [runsError, setRunsError] = useState('')

  // While a schedule's runs are shown, keep them fresh.
  useEffect(() => {
    if (!openId) return undefined
    let cancelled = false
    async function loadRuns() {
      try {
        const data = await request(`/api/schedules/${openId}/runs?limit=8`)
        if (!cancelled) {
          setRuns(data)
          setRunsError('')
        }
      } catch (loadError) {
        if (!cancelled) setRunsError(loadError.message)
      } finally {
        if (!cancelled) setRunsLoading(false)
      }
    }
    setRunsLoading(true)
    loadRuns()
    const interval = window.setInterval(loadRuns, 5000)
    return () => {
      cancelled = true
      window.clearInterval(interval)
    }
  }, [openId])

  function toggleRuns(schedule) {
    setRuns([])
    setRunsError('')
    setOpenId(openId === schedule.id ? null : schedule.id)
  }

  return (
    <>
      <section className="page-heading">
        <div><span className="eyebrow">AUTOMATION</span><h1>Recurring jobs</h1><p>Create jobs automatically on a cron schedule.</p></div>
        <button className="button button-primary new-job-button" onClick={onCreate}><Icon name="plus" size={18} />New schedule</button>
      </section>

      {error && <div className="connection-error" role="alert"><Icon name="alert" size={19} /><div><strong>Couldn't load schedules</strong><span>{error}</span></div></div>}
      {message && <div className="schedule-note" role="status">{message}</div>}

      <section className="jobs-panel">
        <div className="panel-heading">
          <div><div className="panel-title"><h2>Schedules</h2><span className="result-count">{schedules.length}</span></div><p>Each run creates a normal job in the queue</p></div>
        </div>
        {loading && schedules.length === 0 ? (
          <div className="table-message"><span className="spinner" /> Loading schedules...</div>
        ) : schedules.length === 0 ? (
          <div className="empty-state">
            <div className="empty-icon"><Icon name="calendar" size={25} /></div>
            <strong>No schedules yet</strong>
            <p>Create one to run a job every few minutes, hourly or daily.</p>
            <button className="text-button" onClick={onCreate}>New schedule</button>
          </div>
        ) : (
          <div className="table-scroll">
            <table className="schedules-table">
              <thead>
                <tr>
                  <th>Schedule</th>
                  <th>Cron</th>
                  <th>Next run</th>
                  <th>Last run</th>
                  <th>Status</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {schedules.map((schedule) => (
                  <Fragment key={schedule.id}>
                  <tr>
                    <td><div className="job-cell"><span className="job-type">{schedule.name}</span><span className="job-id">{schedule.type}</span></div></td>
                    <td><div className="job-cell"><code className="cron-code">{schedule.cron}</code><span className="job-id">{schedule.timezone}</span></div></td>
                    <td><span className="created-time">{schedule.enabled ? relativeTime(schedule.nextRunAt) : DASH}</span></td>
                    <td><span className="created-time">{relativeTime(schedule.lastRunAt)}</span></td>
                    <td><StatusBadge status={schedule.enabled ? 'ACTIVE' : 'PAUSED'} /></td>
                    <td>
                      <div className="schedule-actions">
                        <button className="button button-secondary button-small" onClick={() => toggleRuns(schedule)}>{openId === schedule.id ? 'Hide runs' : 'Runs'}</button>
                        <button className="button button-secondary button-small" onClick={() => onAction('trigger', schedule)}>Run now</button>
                        <button className="button button-secondary button-small" onClick={() => onAction(schedule.enabled ? 'pause' : 'resume', schedule)}>{schedule.enabled ? 'Pause' : 'Resume'}</button>
                        <button className="button button-danger button-small" onClick={() => onAction('delete', schedule)}>Delete</button>
                      </div>
                    </td>
                  </tr>
                  {openId === schedule.id && (
                    <tr className="runs-row">
                      <td colSpan={6}>
                        {runsError ? (
                          <span className="form-error">{runsError}</span>
                        ) : runsLoading && runs.length === 0 ? (
                          <span className="table-message"><span className="spinner" /> Loading runs...</span>
                        ) : runs.length === 0 ? (
                          <span className="created-time">No runs yet. Use "Run now" to create one.</span>
                        ) : (
                          <div className="run-list">
                            {runs.map((run) => (
                              <button key={run.id} className="run-item" onClick={() => onOpenJob(run)}>
                                <StatusBadge status={run.status} />
                                <span className="created-time">{relativeTime(run.createdAt)}</span>
                                <code className="run-result">{resultSummary(run)}</code>
                              </button>
                            ))}
                          </div>
                        )}
                      </td>
                    </tr>
                  )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

function ClientsPage({ clients, loading, error, message, newKey, onCreate, onToggle, onLimits, onDismissKey }) {
  const [name, setName] = useState('')
  return (
    <>
      <section className="page-heading">
        <div><span className="eyebrow">ACCESS</span><h1>API keys</h1><p>Give each client its own key. A key can submit jobs, nothing else, and every job it submits is recorded.</p></div>
      </section>

      {error && <div className="connection-error" role="alert"><Icon name="alert" size={19} /><div><strong>Couldn't load API clients</strong><span>{error}</span></div></div>}
      {message && <div className="schedule-note" role="status">{message}</div>}
      {newKey && (
        <div className="schedule-note key-note" role="status">
          <strong>Copy this key now. It is shown only once and cannot be recovered.</strong>
          <div><code>{newKey}</code><button className="button button-secondary button-small" onClick={() => navigator.clipboard && navigator.clipboard.writeText(newKey)}>Copy</button><button className="button button-secondary button-small" onClick={onDismissKey}>Done</button></div>
          <small>Use it as the header Authorization: Bearer KEY when you POST to /api/jobs.</small>
        </div>
      )}

      <section className="jobs-panel">
        <div className="panel-heading">
          <div><div className="panel-title"><h2>Clients</h2><span className="result-count">{clients.length}</span></div><p>Only a fingerprint of each key is stored</p></div>
        </div>
        <form className="limit-form" onSubmit={(event) => {
          event.preventDefault()
          onCreate(name.trim())
          setName('')
        }}>
          <input className="input-control" value={name} onChange={(event) => setName(event.target.value)} placeholder="Client name, e.g. billing-service" required maxLength={100} aria-label="Client name" />
          <button type="submit" className="button button-primary"><Icon name="key" size={16} />Create key</button>
        </form>
        {loading && clients.length === 0 ? (
          <div className="table-message"><span className="spinner" /> Loading clients...</div>
        ) : clients.length === 0 ? (
          <div className="empty-state">
            <div className="empty-icon"><Icon name="key" size={25} /></div>
            <strong>No API clients yet</strong>
            <p>Create one above to get a key for an application that submits jobs.</p>
          </div>
        ) : (
          <div className="table-scroll">
            <table className="schedules-table">
              <thead>
                <tr>
                  <th>Client</th>
                  <th>Key starts with</th>
                  <th>Status</th>
                  <th>Last used</th>
                  <th>Jobs</th>
                  <th>Rate limit</th>
                  <th>Daily quota</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {clients.map((client) => (
                  <tr key={client.id}>
                    <td><span className="job-type">{client.name}</span></td>
                    <td><code className="cron-code">{client.keyPrefix}...</code></td>
                    <td><StatusBadge status={client.enabled ? 'ACTIVE' : 'DISABLED'} /></td>
                    <td><span className="created-time">{relativeTime(client.lastUsedAt)}</span></td>
                    <td><span className="attempt-count">{client.jobs}</span></td>
                    <td><span className="created-time">{client.ratePerMinute ? `${client.ratePerMinute} / min` : 'unlimited'}</span></td>
                    <td><span className="created-time">{client.dailyQuota ? `${client.usedToday} / ${client.dailyQuota}` : `${client.usedToday} today`}</span></td>
                    <td><div className="schedule-actions"><button className="button button-secondary button-small" onClick={() => onLimits(client)}>Limits</button><button className="button button-secondary button-small" onClick={() => onToggle(client)}>{client.enabled ? 'Disable' : 'Enable'}</button></div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

function LimitsPage({ limits, loading, error, message, onSave, onDelete }) {
  const [type, setType] = useState('SLOW')
  const [max, setMax] = useState('2')
  return (
    <>
      <section className="page-heading">
        <div><span className="eyebrow">CONTROL</span><h1>Concurrency limits</h1><p>Cap how many jobs of one type may run at the same time, across all workers.</p></div>
      </section>

      {error && <div className="connection-error" role="alert"><Icon name="alert" size={19} /><div><strong>Couldn't load limits</strong><span>{error}</span></div></div>}
      {message && <div className="schedule-note" role="status">{message}</div>}

      <section className="jobs-panel">
        <div className="panel-heading">
          <div><div className="panel-title"><h2>Limits</h2><span className="result-count">{limits.length}</span></div><p>Jobs of a limited type wait in the queue while its slots are taken</p></div>
        </div>
        <form className="limit-form" onSubmit={(event) => {
          event.preventDefault()
          onSave(type.trim(), Number(max))
        }}>
          <input className="input-control" value={type} onChange={(event) => setType(event.target.value)} placeholder="Job type, e.g. SLOW" required maxLength={100} aria-label="Job type" />
          <input className="input-control narrow" type="number" min="1" max="1000" value={max} onChange={(event) => setMax(event.target.value)} required aria-label="Maximum running at once" />
          <button type="submit" className="button button-primary"><Icon name="plus" size={16} />Set limit</button>
        </form>
        {loading && limits.length === 0 ? (
          <div className="table-message"><span className="spinner" /> Loading limits...</div>
        ) : limits.length === 0 ? (
          <div className="empty-state">
            <div className="empty-icon"><Icon name="sliders" size={25} /></div>
            <strong>No limits yet</strong>
            <p>Every job type can use all the worker threads. Add a limit above to cap one.</p>
          </div>
        ) : (
          <div className="table-scroll">
            <table className="schedules-table">
              <thead>
                <tr>
                  <th>Job type</th>
                  <th>Max at once</th>
                  <th>Running now</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {limits.map((limit) => (
                  <tr key={limit.type}>
                    <td><span className="job-type">{limit.type}</span></td>
                    <td><span className="attempt-count">{limit.maxConcurrent}</span></td>
                    <td><span className="attempt-count">{limit.running} / {limit.maxConcurrent}</span></td>
                    <td><div className="schedule-actions"><button className="button button-danger button-small" onClick={() => onDelete(limit.type)}>Remove</button></div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

function JobDrawer({ job, attempts, attemptsLoading, attemptsError, actionBusy, actionError, onCancel, onRetry, onClose }) {
  const canCancel = (job.status === 'PENDING' || job.status === 'RUNNING') && !job.cancelRequested
  const canRetry = job.status === 'DEAD' || job.status === 'FAILED'
  return (
    <div className="drawer-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <aside className="job-drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <div className="drawer-header">
          <div><span className="eyebrow">JOB DETAILS</span><h2 id="drawer-title">{job.type}</h2></div>
          <button className="icon-button" onClick={onClose} aria-label="Close job details"><Icon name="close" /></button>
        </div>
        <div className="drawer-summary"><StatusBadge status={job.status} /><span>#{job.id}</span></div>
        {(canCancel || canRetry) && (
          <div className="drawer-actions">
            {canCancel && <button className="button button-danger" onClick={onCancel} disabled={actionBusy}>Cancel job</button>}
            {canRetry && <button className="button button-primary" onClick={onRetry} disabled={actionBusy}><Icon name="refresh" size={16} />Retry job</button>}
          </div>
        )}
        {actionError && <div className="form-error" role="alert"><Icon name="alert" size={16} />{actionError}</div>}
        {job.status === 'RUNNING' && job.cancelRequested && <div className="schedule-note" role="status">Cancellation requested. The worker stops the job at its next heartbeat.</div>}
        {job.lastError && <div className="last-error"><Icon name="alert" size={17} /><div><strong>Last error</strong><p>{job.lastError}</p></div></div>}
        <div className="detail-grid">
          <div><span>Priority</span><strong>{job.priority ?? DASH}</strong></div>
          <div><span>Attempts</span><strong>{job.attempts ?? 0} / {job.maxAttempts ?? DASH}</strong></div>
          <div><span>Created</span><strong>{formatDate(job.createdAt)}</strong></div>
          <div><span>Updated</span><strong>{formatDate(job.updatedAt)}</strong></div>
          <div><span>Scheduled</span><strong>{formatDate(job.runAt)}</strong></div>
          <div><span>From schedule</span><strong>{job.scheduleId ? `#${shortId(job.scheduleId)}` : DASH}</strong></div>
          <div><span>Idempotency key</span><strong>{job.idempotencyKey ?? DASH}</strong></div>
          <div><span>Submitted by API client</span><strong>{job.clientId ? `#${shortId(job.clientId)}` : DASH}</strong></div>
        </div>
        <div className="drawer-section">
          <div className="section-title"><h3>Payload</h3><span>JSON</span></div>
          <pre className="json-block">{JSON.stringify(job.payload, null, 2) ?? 'null'}</pre>
        </div>
        {job.result != null && (
          <div className="drawer-section">
            <div className="section-title"><h3>Result</h3><span>JSON</span></div>
            <pre className="json-block">{JSON.stringify(job.result, null, 2)}</pre>
          </div>
        )}
        <div className="drawer-section attempts-section">
          <div className="section-title"><h3>Attempts</h3><span>{attemptsLoading ? 'Loading...' : attempts.length}</span></div>
          {attemptsError && <div className="inline-error" role="alert">{attemptsError}</div>}
          {!attemptsLoading && !attemptsError && attempts.length === 0 && <p className="no-attempts">This job hasn't been picked up by a worker yet.</p>}
          {attempts.map((attempt) => (
            <div className="attempt-row" key={attempt.id}>
              <span className={`attempt-icon ${attempt.success === true ? 'attempt-success' : attempt.success === false ? 'attempt-failure' : 'attempt-running'}`}><Icon name={attempt.success === true ? 'check' : attempt.success === false ? 'close' : 'clock'} size={15} /></span>
              <div className="attempt-info">
                <div><strong>Attempt {attempt.id}</strong><span>{attempt.success === true ? 'Succeeded' : attempt.success === false ? 'Failed' : 'Running'}</span></div>
                <small>{attempt.workerId || 'Worker'} - {formatDate(attempt.startedAt)}</small>
                {attempt.error && <p>{attempt.error}</p>}
              </div>
            </div>
          ))}
        </div>
      </aside>
    </div>
  )
}

export default function App() {
  const [view, setView] = useState('overview')
  const [jobs, setJobs] = useState([])
  const [total, setTotal] = useState(0)
  const [stats, setStats] = useState(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [search, setSearch] = useState('')
  const [lastUpdated, setLastUpdated] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [selectedJob, setSelectedJob] = useState(null)
  const [attempts, setAttempts] = useState([])
  const [attemptsLoading, setAttemptsLoading] = useState(false)
  const [attemptsError, setAttemptsError] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [schedules, setSchedules] = useState([])
  const [schedulesLoading, setSchedulesLoading] = useState(true)
  const [schedulesError, setSchedulesError] = useState('')
  const [scheduleMessage, setScheduleMessage] = useState('')
  const [scheduleModal, setScheduleModal] = useState(false)
  const [limits, setLimits] = useState([])
  const [limitsLoading, setLimitsLoading] = useState(true)
  const [limitsError, setLimitsError] = useState('')
  const [limitMessage, setLimitMessage] = useState('')
  const [clients, setClients] = useState([])
  const [clientsLoading, setClientsLoading] = useState(true)
  const [clientsError, setClientsError] = useState('')
  const [clientMessage, setClientMessage] = useState('')
  const [newKey, setNewKey] = useState('')
  const [notice, setNotice] = useState('')
  const selectedId = selectedJob ? selectedJob.id : null

  // One refresh loads the current page of jobs (filtered by status on the server) and the overall stats.
  const refresh = useCallback(async (initial = false) => {
    if (initial) setLoading(true)
    try {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(page * PAGE_SIZE) })
      if (filter !== 'ALL') params.set('status', filter)
      const [list, statsResult] = await Promise.all([
        requestPage(`/api/jobs?${params.toString()}`),
        request('/api/stats'),
      ])
      const lastPage = Math.max(0, Math.ceil(list.total / PAGE_SIZE) - 1)
      if (page > lastPage) {
        setPage(lastPage)
        return
      }
      setJobs(list.items)
      setTotal(list.total)
      setStats(statsResult)
      setError('')
      setLastUpdated(new Date())
    } catch (loadError) {
      setError(loadError.message)
    } finally {
      if (initial) setLoading(false)
    }
  }, [page, filter])

  useEffect(() => {
    refresh(true)
    const interval = window.setInterval(() => refresh(), 5000)
    return () => window.clearInterval(interval)
  }, [refresh])

  const loadSchedules = useCallback(async () => {
    try {
      setSchedules(await request('/api/schedules'))
      setSchedulesError('')
    } catch (loadError) {
      setSchedulesError(loadError.message)
    } finally {
      setSchedulesLoading(false)
    }
  }, [])

  // Schedules are only polled while their page is open.
  useEffect(() => {
    if (view !== 'schedules') return undefined
    loadSchedules()
    const interval = window.setInterval(loadSchedules, 5000)
    return () => window.clearInterval(interval)
  }, [view, loadSchedules])

  const loadLimits = useCallback(async () => {
    try {
      setLimits(await request('/api/limits'))
      setLimitsError('')
    } catch (loadError) {
      setLimitsError(loadError.message)
    } finally {
      setLimitsLoading(false)
    }
  }, [])

  const loadClients = useCallback(async () => {
    try {
      setClients(await request('/api/clients'))
      setClientsError('')
    } catch (loadError) {
      setClientsError(loadError.message)
    } finally {
      setClientsLoading(false)
    }
  }, [])

  // Clients are only loaded while their page is open (listing them needs the admin login on a protected server).
  useEffect(() => {
    if (view !== 'clients') return undefined
    loadClients()
    const interval = window.setInterval(loadClients, 10000)
    return () => window.clearInterval(interval)
  }, [view, loadClients])

  // Limits are only polled while their page is open.
  useEffect(() => {
    if (view !== 'limits') return undefined
    loadLimits()
    const interval = window.setInterval(loadLimits, 5000)
    return () => window.clearInterval(interval)
  }, [view, loadLimits])

  // While the drawer is open, keep the selected job and its attempts live.
  useEffect(() => {
    if (!selectedId) return undefined
    let cancelled = false
    async function refreshSelected(first) {
      if (first) {
        setAttemptsLoading(true)
        setAttemptsError('')
      }
      try {
        const [job, attemptList] = await Promise.all([
          request(`/api/jobs/${selectedId}`),
          request(`/api/jobs/${selectedId}/attempts`),
        ])
        if (cancelled) return
        setSelectedJob(job)
        setAttempts(attemptList)
        setAttemptsError('')
      } catch (loadError) {
        if (!cancelled) setAttemptsError(loadError.message)
      } finally {
        if (first && !cancelled) setAttemptsLoading(false)
      }
    }
    refreshSelected(true)
    const interval = window.setInterval(() => refreshSelected(false), 4000)
    return () => {
      cancelled = true
      window.clearInterval(interval)
    }
  }, [selectedId])

  const byStatus = stats ? stats.byStatus : {}
  const counts = {
    total: stats ? stats.total : 0,
    pending: byStatus.PENDING ?? 0,
    running: byStatus.RUNNING ?? 0,
    succeeded: byStatus.SUCCEEDED ?? 0,
    dead: (byStatus.DEAD ?? 0) + (byStatus.FAILED ?? 0),
  }
  const statsLoading = loading && !stats

  // The search box filters the jobs on the current page only; the status filter and paging run on the server.
  const visibleJobs = useMemo(() => {
    const query = search.trim().toLowerCase()
    if (!query) return jobs
    return jobs.filter((job) => `${job.type} ${job.id} ${job.status}`.toLowerCase().includes(query))
  }, [jobs, search])

  const firstShown = total === 0 ? 0 : page * PAGE_SIZE + 1
  const lastShown = page * PAGE_SIZE + jobs.length

  function openJob(job) {
    setAttempts([])
    setAttemptsError('')
    setActionError('')
    setSelectedJob(job)
  }

  function handleSubmitted(job, replayed) {
    setSubmitting(false)
    setPage(0)
    openJob(job)
    refresh()
    if (replayed) {
      setNotice(`An identical job already exists (#${shortId(job.id)}, ${job.status}). No duplicate was created.`)
      window.setTimeout(() => setNotice(''), 8000)
    }
  }

  function changeFilter(value) {
    setFilter(value)
    setPage(0)
  }

  function clearFilters() {
    setFilter('ALL')
    setSearch('')
    setPage(0)
  }

  function showJobs() {
    setView('overview')
    window.setTimeout(() => document.getElementById('jobs-section')?.scrollIntoView({ behavior: 'smooth' }), 60)
  }

  function handleScheduleCreated(schedule) {
    setScheduleModal(false)
    setScheduleMessage(`Created schedule "${schedule.name}". It fires ${relativeTime(schedule.nextRunAt)}.`)
    loadSchedules()
  }

  async function createClient(name) {
    setClientMessage('')
    try {
      const created = await request('/api/clients', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      })
      setNewKey(created.apiKey)
      setClientMessage(`Created "${created.client.name}".`)
    } catch (failure) {
      setClientMessage(failure.message)
    } finally {
      loadClients()
    }
  }

  async function setClientLimits(client) {
    const rateText = window.prompt(`Jobs per minute for "${client.name}" (leave empty for unlimited)`, String(client.ratePerMinute ?? ''))
    if (rateText === null) return
    const dailyText = window.prompt('Jobs per day, counted in UTC (leave empty for unlimited)', String(client.dailyQuota ?? ''))
    if (dailyText === null) return
    const toNumber = (text) => (text.trim() === '' ? null : Number(text))
    const ratePerMinute = toNumber(rateText)
    const dailyQuota = toNumber(dailyText)
    if ([ratePerMinute, dailyQuota].some((value) => value !== null && (!Number.isInteger(value) || value < 1))) {
      setClientMessage('Limits must be whole numbers of 1 or more, or empty for unlimited.')
      return
    }
    setClientMessage('')
    try {
      await request(`/api/clients/${client.id}/limits`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ratePerMinute, dailyQuota }),
      })
      setClientMessage(`Limits saved for "${client.name}".`)
    } catch (failure) {
      setClientMessage(failure.message)
    } finally {
      loadClients()
    }
  }

  async function toggleClient(client) {
    setClientMessage('')
    try {
      await request(`/api/clients/${client.id}/${client.enabled ? 'disable' : 'enable'}`, { method: 'POST' })
    } catch (failure) {
      setClientMessage(failure.message)
    } finally {
      loadClients()
    }
  }

  async function saveLimit(type, maxConcurrent) {
    setLimitMessage('')
    try {
      await request('/api/limits', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type, maxConcurrent }),
      })
      setLimitMessage(`At most ${maxConcurrent} ${type} job(s) will run at the same time.`)
    } catch (failure) {
      setLimitMessage(failure.message)
    } finally {
      loadLimits()
    }
  }

  async function deleteLimit(type) {
    setLimitMessage('')
    try {
      await request(`/api/limits/${encodeURIComponent(type)}`, { method: 'DELETE' })
      setLimitMessage(`${type} is no longer limited.`)
    } catch (failure) {
      setLimitMessage(failure.message)
    } finally {
      loadLimits()
    }
  }

  async function runAction(kind) {
    if (!selectedJob) return
    if (kind === 'cancel' && !window.confirm(selectedJob.status === 'RUNNING' ? 'Ask the worker to stop this running job?' : 'Cancel this job? It will not run.')) return
    setActionBusy(true)
    setActionError('')
    try {
      const updated = kind === 'cancel'
        ? await request(`/api/jobs/${selectedJob.id}`, { method: 'DELETE' })
        : await request(`/api/jobs/${selectedJob.id}/retry`, { method: 'POST' })
      setSelectedJob(updated)
    } catch (actionFailure) {
      setActionError(actionFailure.message)
    } finally {
      setActionBusy(false)
      refresh()
    }
  }

  async function scheduleAction(kind, schedule) {
    if (kind === 'delete' && !window.confirm(`Delete schedule "${schedule.name}"? Jobs it already created are kept.`)) return
    setScheduleMessage('')
    try {
      if (kind === 'trigger') {
        const job = await request(`/api/schedules/${schedule.id}/trigger`, { method: 'POST' })
        setScheduleMessage(`Created job #${shortId(job.id)} from "${schedule.name}".`)
      } else if (kind === 'delete') {
        await request(`/api/schedules/${schedule.id}`, { method: 'DELETE' })
        setScheduleMessage(`Deleted "${schedule.name}".`)
      } else {
        await request(`/api/schedules/${schedule.id}/${kind}`, { method: 'POST' })
      }
    } catch (actionFailure) {
      setScheduleMessage(actionFailure.message)
    } finally {
      loadSchedules()
      refresh()
    }
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="#" aria-label="Queueboard home">
          <span className="brand-mark"><span /><span /><span /></span>
          <span>queue<span className="brand-light">board</span></span>
        </a>
        <div className="workspace-switcher">
          <span className="workspace-icon">JQ</span>
          <span className="workspace-copy"><strong>Job Queue</strong><small>Workspace</small></span>
          <span className="workspace-chevron"><Icon name="chevronDown" size={16} /></span>
        </div>
        <div className="nav-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Main navigation">
          <button className={view === 'overview' ? 'nav-item active' : 'nav-item'} onClick={() => setView('overview')}><Icon name="grid" size={18} />Overview</button>
          <button className="nav-item" onClick={showJobs}><Icon name="layers" size={18} />Jobs<span className="nav-count">{counts.total}</span></button>
          <button className={view === 'schedules' ? 'nav-item active' : 'nav-item'} onClick={() => setView('schedules')}><Icon name="calendar" size={18} />Schedules</button>
          <button className={view === 'limits' ? 'nav-item active' : 'nav-item'} onClick={() => setView('limits')}><Icon name="sliders" size={18} />Limits</button>
          <button className={view === 'clients' ? 'nav-item active' : 'nav-item'} onClick={() => setView('clients')}><Icon name="key" size={18} />API keys</button>
        </nav>
        <div className="sidebar-bottom">
          <div className="api-status"><span className={`connection-dot ${error ? 'offline' : ''}`} /><span><strong>{loading ? 'Connecting...' : error ? 'API unavailable' : 'API connected'}</strong><small>{error ? 'Check your connection' : window.location.host}</small></span></div>
          <div className="user-card"><span className="avatar">JD</span><span><strong>Job Dashboard</strong><small>{window.location.hostname === 'localhost' ? 'Local environment' : 'Live demo'}</small></span><span className="user-menu">...</span></div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumbs"><span>Job Queue</span><Icon name="chevron" size={14} /><strong>{view === 'clients' ? 'API keys' : view === 'limits' ? 'Limits' : view === 'schedules' ? 'Schedules' : 'Overview'}</strong></div>
          <div className="topbar-right"><span className="live-indicator"><span />Live updates</span><button className="icon-button refresh-button" onClick={() => (view === 'clients' ? loadClients() : view === 'limits' ? loadLimits() : view === 'schedules' ? loadSchedules() : refresh())} aria-label="Refresh"><Icon name="refresh" /></button></div>
        </header>

        <div className="page-content">
          {view === 'clients' ? (
            <ClientsPage clients={clients} loading={clientsLoading} error={clientsError} message={clientMessage} newKey={newKey} onCreate={createClient} onToggle={toggleClient} onLimits={setClientLimits} onDismissKey={() => setNewKey('')} />
          ) : view === 'limits' ? (
            <LimitsPage limits={limits} loading={limitsLoading} error={limitsError} message={limitMessage} onSave={saveLimit} onDelete={deleteLimit} />
          ) : view === 'schedules' ? (
            <SchedulesPage schedules={schedules} loading={schedulesLoading} error={schedulesError} message={scheduleMessage} onCreate={() => setScheduleModal(true)} onAction={scheduleAction} onOpenJob={openJob} />
          ) : (
            <>
              <section className="page-heading">
                <div><span className="eyebrow">MONITORING</span><h1>Job overview</h1><p>Track and manage the work flowing through your queue.</p></div>
                <button className="button button-primary new-job-button" onClick={() => setSubmitting(true)}><Icon name="plus" size={18} />Create job</button>
              </section>

              {error && <div className="connection-error" role="alert"><Icon name="alert" size={19} /><div><strong>Couldn't load jobs</strong><span>{error} - Check that the API is running.</span></div><button className="text-button" onClick={() => refresh(true)}>Try again</button></div>}

              {notice && <div className="schedule-note" role="status">{notice}</div>}

              <section className="metrics-grid" aria-label="Job metrics">
                <MetricCard label="Total jobs" count={counts.total} icon="layers" tone="metric-purple" loading={statsLoading} />
                <MetricCard label="Pending" count={counts.pending} icon="clock" tone="metric-amber" loading={statsLoading} />
                <MetricCard label="Running" count={counts.running} icon="activity" tone="metric-blue" loading={statsLoading} />
                <MetricCard label="Succeeded" count={counts.succeeded} icon="check" tone="metric-green" loading={statsLoading} />
                <MetricCard label="Dead letters" count={counts.dead} icon="alert" tone="metric-red" loading={statsLoading} />
              </section>

              {stats && (
                <div className="stats-strip">
                  <span className="stat-item"><span className="stat-dot" />Finished in the last minute: <strong>{stats.succeededLastMinute}</strong></span>
                  <span className="stat-item"><span className="stat-dot amber" />Longest wait for a runnable job: <strong>{formatWait(stats.oldestDuePendingSeconds)}</strong></span>
                </div>
              )}

              <section className="jobs-panel" id="jobs-section">
                <div className="panel-heading">
                  <div><div className="panel-title"><h2>Jobs</h2><span className="result-count">{total}</span></div><p>Newest first <span>- {PAGE_SIZE} per page</span></p></div>
                  <span className="updated-time">{lastUpdated ? `Updated ${relativeTime(lastUpdated)}` : 'Waiting for data'}</span>
                </div>
                <div className="table-toolbar">
                  <label className="search-field"><Icon name="search" size={17} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search this page..." aria-label="Search jobs on this page" />{search && <button onClick={() => setSearch('')} aria-label="Clear search"><Icon name="close" size={14} /></button>}</label>
                  <label className="filter-field"><Icon name="sliders" size={16} /><select value={filter} onChange={(event) => changeFilter(event.target.value)} aria-label="Filter by status"><option value="ALL">All statuses</option>{STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}</select></label>
                </div>
                <JobTable jobs={visibleJobs} loading={loading} onSelect={openJob} onClear={clearFilters} />
                <div className="table-footer">
                  <span>Showing <strong>{firstShown}-{lastShown}</strong> of <strong>{total}</strong> jobs</span>
                  <span className="pager">
                    <button className="button button-secondary button-small" onClick={() => setPage(page - 1)} disabled={page === 0}>Previous</button>
                    <span className="pager-page">Page {page + 1}</span>
                    <button className="button button-secondary button-small" onClick={() => setPage(page + 1)} disabled={(page + 1) * PAGE_SIZE >= total}>Next</button>
                  </span>
                  <span className="auto-refresh"><span />Auto-refreshes every 5 seconds</span>
                </div>
              </section>
            </>
          )}
          <footer className="page-footer"><span>Queueboard <span>|</span> Job Queue Console</span><span>{lastUpdated ? `Last synced ${formatDate(lastUpdated)}` : 'Connecting to API...'}</span></footer>
        </div>
      </main>

      {submitting && <SubmitModal onClose={() => setSubmitting(false)} onSubmitted={handleSubmitted} />}
      {scheduleModal && <ScheduleModal onClose={() => setScheduleModal(false)} onCreated={handleScheduleCreated} />}
      {selectedJob && <JobDrawer job={selectedJob} attempts={attempts} attemptsLoading={attemptsLoading} attemptsError={attemptsError} actionBusy={actionBusy} actionError={actionError} onCancel={() => runAction('cancel')} onRetry={() => runAction('retry')} onClose={() => setSelectedJob(null)} />}
    </div>
  )
}
