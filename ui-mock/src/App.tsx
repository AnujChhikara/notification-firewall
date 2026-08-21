import React, { useState } from "react";
import { Phone } from "./ui";
import { navItems } from "./data";
import {
  Onboarding,
  Home,
  Program,
  Results,
  Pods,
  Intervention,
} from "./screens";

type View = "onboarding" | "home" | "program" | "results" | "pods";

export function App() {
  const [view, setView] = useState<View>("onboarding");
  const [overlay, setOverlay] = useState(false);

  const inApp = view !== "onboarding";

  return (
    <div className="stage">
      <Phone>
        {view === "onboarding" && (
          <Onboarding onDone={() => setView("home")} />
        )}
        {view === "home" && <Home onIntervene={() => setOverlay(true)} />}
        {view === "program" && <Program />}
        {view === "results" && <Results />}
        {view === "pods" && <Pods />}

        {inApp && (
          <nav className="nav">
            {navItems.map((n) => (
              <button
                key={n.id}
                className={view === n.id ? "on" : ""}
                onClick={() => setView(n.id as View)}
              >
                <span className="ic">{n.ic}</span>
                {n.label}
              </button>
            ))}
          </nav>
        )}

        {overlay && <Intervention onClose={() => setOverlay(false)} />}
      </Phone>

      <div className="stage-hint">
        {view === "onboarding" ? (
          <>Attention Platform — UI mock · <b>answer the intake to enter the app</b></>
        ) : (
          <>tap <b>Try it now</b> on Home to see the intervention moment · <b onClick={() => setView("onboarding")} style={{ cursor: "pointer" }}>↺ replay intro</b></>
        )}
      </div>
    </div>
  );
}
