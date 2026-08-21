// Mock content for the UI prototype. Copy follows the Guidance System voice
// from the master design spec: warm, concrete, non-judgmental.

export type Bucket = "let-through" | "silence" | "capture" | "off";

export const protections: {
  name: string;
  glyph: string;
  status: string;
  bucket: Bucket;
}[] = [
  { name: "Instagram", glyph: "📸", status: "Friction on open", bucket: "capture" },
  { name: "TikTok", glyph: "🎵", status: "Captured to inbox", bucket: "capture" },
  { name: "YouTube", glyph: "▶️", status: "Silenced till 6pm", bucket: "silence" },
  { name: "Messages", glyph: "💬", status: "Let through", bucket: "let-through" },
  { name: "Slack", glyph: "💼", status: "Batched hourly", bucket: "silence" },
];

export const interventionOptions: {
  icon: string;
  title: string;
  sub: string;
}[] = [
  { icon: "🫁", title: "Breathe", sub: "Three slow breaths, together" },
  { icon: "🚶", title: "Take a 2-minute walk", sub: "Move — it sharpens focus" },
  { icon: "📖", title: "Do what you meant to", sub: "You said: read a few pages" },
  { icon: "⏱️", title: "Okay, 5 minutes", sub: "Timed — we'll check back in" },
];

export const trackSteps: {
  n: number;
  state: "done" | "current" | "todo";
  title: string;
  sub: string;
}[] = [
  { n: 1, state: "done", title: "Understand the pull", sub: "Why the scroll isn't a willpower problem" },
  { n: 2, state: "done", title: "Silence the noise", sub: "Turn off notifications for one app" },
  { n: 3, state: "current", title: "Add a pause", sub: "A breath before Instagram opens" },
  { n: 4, state: "todo", title: "Reclaim your mornings", sub: "Delay the first scroll by 10 minutes" },
  { n: 5, state: "todo", title: "Give it somewhere to go", sub: "Trade the scroll for one small thing" },
  { n: 6, state: "todo", title: "Graduate", sub: "Protections stay on, quietly" },
];

export const results = {
  reclaimedHrs: 6.5,
  opensDelta: -42,
  opensNow: 31,
  opensBase: 54,
  longestBlock: 52,
  longestBase: 12,
  abandoned: 36,
  sleepNights: 5,
};

export const weeklyBars = [40, 62, 35, 78, 55, 88, 70]; // protected-focus minutes-ish

export const pod = {
  name: "Weekend Warriors",
  track: "Tame the Scroll",
  members: [
    { name: "You", initial: "Y", color: "#8fc2a6", note: "Protected 4 blocks today", on: true },
    { name: "Ravi", initial: "R", color: "#e4b982", note: "Delayed the morning scroll · day 5", on: false },
    { name: "Mia", initial: "M", color: "#9db2e0", note: "Slipped last night — back on it", on: false },
    { name: "Dev", initial: "D", color: "#d98d84", note: "Longest block: 41 min 🎉", on: false },
  ],
};

export const assessmentQuestions = [
  {
    q: "When you pick up your phone with no reason, where do you end up?",
    opts: ["Instagram / TikTok reels", "News / doomscrolling", "YouTube for hours", "Just checking, over and over"],
  },
  {
    q: "When does it hurt the most?",
    opts: ["First thing in the morning", "Late at night in bed", "When I should be working", "All day, in bursts"],
  },
  {
    q: "What would a good week give back to you?",
    opts: ["Calmer mornings", "Real sleep", "Focused work", "Just… less noise"],
  },
];

export const habitCard = {
  eyebrow: "Today's small thing",
  title: "Charge your phone across the room tonight",
  why: "It's the single highest-yield change for sleep — and sleep is what your attention runs on.",
  cta: "I'll try it tonight",
};

export const navItems = [
  { id: "home", label: "Home", ic: "◇" },
  { id: "program", label: "Program", ic: "❯" },
  { id: "results", label: "Results", ic: "◈" },
  { id: "pods", label: "Pods", ic: "◎" },
] as const;
