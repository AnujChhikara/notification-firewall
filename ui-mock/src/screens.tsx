import React, { useState } from "react";
import {
  Card,
  Dot,
  Button,
  Chip,
  Metric,
  Bar,
  BreathingOrb,
  FocusRing,
  ScreenHeader,
} from "./ui";
import {
  protections,
  interventionOptions,
  trackSteps,
  results,
  weeklyBars,
  pod,
  assessmentQuestions,
  habitCard,
} from "./data";

/* ============================ ONBOARDING + ASSESSMENT ==================== */

export function Onboarding({ onDone }: { onDone: () => void }) {
  const [step, setStep] = useState(0); // 0 welcome, 1-3 questions, 4 diagnosis
  const [answers, setAnswers] = useState<number[]>([-1, -1, -1]);
  const total = 5;

  if (step === 0) {
    return (
      <div className="hero fade-in">
        <div className="brandmark">
          <span className="bm-dot" /> Still
        </div>
        <div className="hero-mid">
          <p className="eyebrow">A quieter kind of phone</p>
          <p className="voice lg">
            Your attention isn't broken. It's being <em>taken</em> — by apps
            built like slot machines.
          </p>
          <p className="subtitle">
            Still is a guide that helps you take it back. No shame, no
            willpower — we just change how your phone works.
          </p>
        </div>
        <div className="stack">
          <Button variant="primary" onClick={() => setStep(1)}>
            Find out what's pulling at you
          </Button>
          <p className="center" style={{ color: "var(--faint)", fontSize: 12 }}>
            Takes about a minute
          </p>
        </div>
      </div>
    );
  }

  if (step >= 1 && step <= 3) {
    const qi = step - 1;
    const q = assessmentQuestions[qi];
    const pick = (i: number) => {
      const next = [...answers];
      next[qi] = i;
      setAnswers(next);
    };
    return (
      <div className="hero fade-in" key={step}>
        <Progress step={step} total={total} />
        <div className="hero-mid">
          <p className="eyebrow muted">Question {qi + 1} of 3</p>
          <p className="voice">{q.q}</p>
          <div className="stack mt-8">
            {q.opts.map((o, i) => (
              <button
                key={o}
                className={"opt" + (answers[qi] === i ? " " : "")}
                style={
                  answers[qi] === i
                    ? { borderColor: "var(--accent-dim)", background: "var(--accent-glow)" }
                    : undefined
                }
                onClick={() => pick(i)}
              >
                <span className="ot">{o}</span>
              </button>
            ))}
          </div>
        </div>
        <Button
          variant={answers[qi] === -1 ? "ghost" : "primary"}
          onClick={() => setStep(step + 1)}
        >
          {answers[qi] === -1 ? "Skip" : "Continue"}
        </Button>
      </div>
    );
  }

  // step 4 — diagnosis
  return (
    <div className="hero fade-in">
      <Progress step={4} total={total} />
      <div className="hero-mid">
        <p className="eyebrow">Here's what's going on</p>
        <p className="voice lg">
          You're caught in the <em>morning scroll</em> — the reels loop that
          eats your first hour and sets the tone for the day.
        </p>
        <Card variant="accent">
          <p className="subtitle" style={{ margin: 0 }}>
            Good news: this is one of the most fixable patterns. We've picked a
            track for it, and set up your phone to help — starting now.
          </p>
        </Card>
        <div className="row-between mt-8">
          <span className="tag accent">Your track</span>
          <span style={{ fontWeight: 600 }}>Tame the Scroll · 6 steps</span>
        </div>
      </div>
      <Button variant="primary" onClick={onDone}>
        Start my track
      </Button>
    </div>
  );
}

function Progress({ step, total }: { step: number; total: number }) {
  return (
    <div className="dots-progress">
      {Array.from({ length: total }).map((_, i) => (
        <i key={i} className={i < step ? "on" : ""} />
      ))}
    </div>
  );
}

/* ================================== HOME ================================= */

export function Home({ onIntervene }: { onIntervene: () => void }) {
  return (
    <div className="screen fade-in">
      <ScreenHeader eyebrow="Tuesday · morning" title="You're protected." />

      <Card padLg>
        <div className="row-between">
          <FocusRing pct={62} n="3" label="blocks kept" />
          <div style={{ flex: 1, marginLeft: 6 }}>
            <p className="voice sm" style={{ margin: 0 }}>
              A calm start. Your reels are captured, notifications are quiet.
            </p>
            <div className="row-between mt-16">
              <Metric n="2h 40m" label="reclaimed today" tone="accent" />
            </div>
          </div>
        </div>
      </Card>

      <p className="section-label">Your next step</p>
      <Card variant="accent">
        <div className="row-between">
          <div>
            <span className="tag accent">Step 3 of 6</span>
            <p className="row-title mt-8" style={{ fontSize: 17 }}>
              Add a pause
            </p>
            <p className="row-sub" style={{ whiteSpace: "normal" }}>
              A single breath before Instagram opens.
            </p>
          </div>
        </div>
        <div className="mt-16">
          <Button variant="primary" onClick={onIntervene}>
            Try it now
          </Button>
        </div>
      </Card>

      <p className="section-label">{habitCard.eyebrow}</p>
      <Card>
        <p className="voice sm" style={{ margin: 0 }}>
          {habitCard.title}
        </p>
        <p className="row-sub mt-8" style={{ whiteSpace: "normal" }}>
          {habitCard.why}
        </p>
        <div className="chip-row mt-16">
          <Chip on>{habitCard.cta}</Chip>
          <Chip>Not tonight</Chip>
        </div>
      </Card>

      <p className="section-label">Protecting you now</p>
      <Card className="quiet">
        {protections.map((p, i) => (
          <div className="row" key={p.name}>
            <span className="glyph">{p.glyph}</span>
            <div className="row-main">
              <p className="row-title">{p.name}</p>
              <p className="row-sub">{p.status}</p>
            </div>
            <Dot bucket={p.bucket} />
          </div>
        ))}
      </Card>
    </div>
  );
}

/* ================================ PROGRAM =============================== */

export function Program() {
  return (
    <div className="screen fade-in">
      <ScreenHeader
        eyebrow="Your track"
        title="Tame the Scroll"
        subtitle="Six small steps. Each one teaches a little and quietly changes how your phone works."
      />
      <Card>
        <div className="row-between">
          <span className="tag">2 of 6 done</span>
          <span style={{ color: "var(--muted)", fontSize: 13 }}>~4 min today</span>
        </div>
        <div className="mt-16">
          <Bar pct={33} />
        </div>
      </Card>

      <p className="section-label">Steps</p>
      <Card className="quiet">
        {trackSteps.map((s) => (
          <div
            key={s.n}
            className={"step " + (s.state === "todo" ? "" : s.state)}
            style={{ padding: "10px 0" }}
          >
            <span className="marker">{s.state === "done" ? "✓" : s.n}</span>
            <div className="step-body">
              <p className="st">{s.title}</p>
              <p className="ss">{s.sub}</p>
            </div>
          </div>
        ))}
      </Card>

      <p className="section-label">Skills you're learning</p>
      <div className="chip-row">
        <Chip>Spot the trigger</Chip>
        <Chip>If-then plans</Chip>
        <Chip>Ride the urge</Chip>
        <Chip>What matters more</Chip>
      </div>
    </div>
  );
}

/* ================================ RESULTS =============================== */

export function Results() {
  return (
    <div className="screen fade-in">
      <ScreenHeader eyebrow="This week" title="You reclaimed 6.5 hours." lg />

      <Card variant="accent" padLg>
        <div className="grid-2">
          <Metric n="6.5h" label="time reclaimed" tone="warm" />
          <Metric n="−42%" label="compulsive opens" delta="vs your first week" tone="accent" />
        </div>
      </Card>

      <div className="grid-2 mt-16" style={{ marginTop: 12 }}>
        <Card>
          <Metric n="52m" label="longest focus block" delta="was 12m" tone="accent" />
        </Card>
        <Card>
          <Metric n="36%" label="times you walked away" />
        </Card>
      </div>

      <p className="section-label">Focus you protected</p>
      <Card>
        <div
          style={{
            display: "flex",
            alignItems: "flex-end",
            gap: 8,
            height: 92,
          }}
        >
          {weeklyBars.map((h, i) => (
            <div key={i} style={{ flex: 1, textAlign: "center" }}>
              <div
                style={{
                  height: h + "%",
                  background:
                    i === 5
                      ? "linear-gradient(180deg, var(--accent-strong), var(--accent-dim))"
                      : "var(--surface-3)",
                  borderRadius: 6,
                }}
              />
              <span style={{ fontSize: 10, color: "var(--faint)" }}>
                {"SMTWTFS"[i]}
              </span>
            </div>
          ))}
        </div>
      </Card>

      <Card className="mt-16" variant="quiet">
        <p className="voice sm" style={{ margin: 0 }}>
          You slept with your phone out of the room 5 nights. That's why the
          mornings felt easier.
        </p>
      </Card>

      <p className="section-label">Share the win</p>
      <Card>
        <p className="row-sub" style={{ whiteSpace: "normal" }}>
          Bring a friend into your pod — you're more likely to keep going
          together.
        </p>
        <div className="stack mt-16">
          <Button variant="primary">Invite a friend</Button>
          <Button variant="ghost">Share this week</Button>
        </div>
      </Card>
    </div>
  );
}

/* ================================= PODS ================================= */

export function Pods() {
  return (
    <div className="screen fade-in">
      <ScreenHeader eyebrow={pod.name} title="Your pod" />
      <div className="row-between" style={{ padding: "0 2px 4px" }}>
        <span className="tag accent">Together on · {pod.track}</span>
        <div className="avatars">
          {pod.members.map((m) => (
            <span key={m.name} className="av" style={{ background: m.color }}>
              {m.initial}
            </span>
          ))}
        </div>
      </div>

      <Card className="quiet mt-16">
        {pod.members.map((m) => (
          <div className="row" key={m.name}>
            <span className="av" style={{ background: m.color, margin: 0 }}>
              {m.initial}
            </span>
            <div className="row-main">
              <p className="row-title">{m.name}</p>
              <p className="row-sub">{m.note}</p>
            </div>
            <span className={"dot " + (m.on ? "let-through" : "off")} />
          </div>
        ))}
      </Card>

      <Card variant="accent" className="mt-16">
        <p className="voice sm" style={{ margin: 0 }}>
          Mia slipped last night and came back today. Send her a little
          encouragement?
        </p>
        <div className="chip-row mt-16">
          <Chip on>👏 Proud of you</Chip>
          <Chip>💬 You've got this</Chip>
        </div>
      </Card>

      <div className="stack mt-16">
        <Button variant="ghost">Invite a friend to the pod</Button>
      </div>
    </div>
  );
}

/* ============================ INTERVENTION OVERLAY ====================== */

export function Intervention({ onClose }: { onClose: () => void }) {
  return (
    <div className="overlay">
      <div className="ov-top">
        <span className="tag">Opening Instagram</span>
      </div>

      <div className="ov-mid">
        <BreathingOrb caption="breathe…" />
        <p className="voice lg">
          Hold on a second. You said mornings were for a <em>calm start</em>.
        </p>
        <p className="subtitle" style={{ margin: 0 }}>
          No judgment — just a pause. What do you actually want right now?
        </p>
      </div>

      <div className="ov-actions">
        {interventionOptions.map((o) => (
          <button className="opt" key={o.title} onClick={onClose}>
            <span className="oi">{o.icon}</span>
            <span>
              <div className="ot">{o.title}</div>
              <div className="os">{o.sub}</div>
            </span>
          </button>
        ))}
        <button className="opt continue" onClick={onClose}>
          Continue to Instagram
        </button>
      </div>
    </div>
  );
}
