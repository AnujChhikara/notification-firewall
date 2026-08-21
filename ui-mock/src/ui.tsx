import React from "react";
import type { Bucket } from "./data";

export function StatusBar() {
  return (
    <div className="statusbar">
      <span>9:41</span>
      <span className="dots">
        <span>􀙇</span>
        <span>􀙟</span>
        <span>􀛨</span>
      </span>
    </div>
  );
}

export function Phone({ children }: { children: React.ReactNode }) {
  return (
    <div className="phone">
      <StatusBar />
      {children}
    </div>
  );
}

export function ScreenHeader({
  eyebrow,
  title,
  subtitle,
  lg,
}: {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  lg?: boolean;
}) {
  return (
    <div className="header">
      {eyebrow && <p className="eyebrow">{eyebrow}</p>}
      <h1 className={"title" + (lg ? " lg" : "")}>{title}</h1>
      {subtitle && <p className="subtitle">{subtitle}</p>}
    </div>
  );
}

export function Card({
  children,
  variant,
  padLg,
  className,
}: {
  children: React.ReactNode;
  variant?: "quiet" | "accent";
  padLg?: boolean;
  className?: string;
}) {
  return (
    <div
      className={
        "card" +
        (variant ? " " + variant : "") +
        (padLg ? " pad-lg" : "") +
        (className ? " " + className : "")
      }
    >
      {children}
    </div>
  );
}

export function Dot({ bucket }: { bucket: Bucket }) {
  return <span className={"dot " + bucket} />;
}

export function Button({
  children,
  variant,
  onClick,
}: {
  children: React.ReactNode;
  variant?: "primary" | "ghost" | "link";
  onClick?: () => void;
}) {
  return (
    <button className={"btn" + (variant ? " " + variant : "")} onClick={onClick}>
      {children}
    </button>
  );
}

export function Chip({
  children,
  on,
  onClick,
}: {
  children: React.ReactNode;
  on?: boolean;
  onClick?: () => void;
}) {
  return (
    <button className={"chip" + (on ? " on" : "")} onClick={onClick}>
      {children}
    </button>
  );
}

export function Metric({
  n,
  label,
  delta,
  tone,
}: {
  n: string;
  label: string;
  delta?: string;
  tone?: "accent" | "warm";
}) {
  return (
    <div className="metric">
      <span className={"n" + (tone ? " " + tone : "")}>{n}</span>
      <span className="l">{label}</span>
      {delta && <span className="delta">{delta}</span>}
    </div>
  );
}

export function Bar({ pct }: { pct: number }) {
  return (
    <div className="bar">
      <span style={{ width: pct + "%" }} />
    </div>
  );
}

/** The signature: a slow breathing orb that guides a breath. */
export function BreathingOrb({ caption = "breathe in…" }: { caption?: string }) {
  return (
    <div className="orb-wrap">
      <div className="orb" role="img" aria-label="A slow breathing guide">
        <span className="ring r1" />
        <span className="ring r2" />
        <span className="ring r3" />
        <span className="core" />
      </div>
      <div className="orb-caption">{caption}</div>
    </div>
  );
}

export function FocusRing({
  pct,
  n,
  label,
}: {
  pct: number;
  n: string;
  label: string;
}) {
  return (
    <div className="focus-ring" style={{ ["--pct" as any]: pct }}>
      <div className="fr-inner">
        <div className="fr-n">{n}</div>
        <div className="fr-l">{label}</div>
      </div>
    </div>
  );
}
