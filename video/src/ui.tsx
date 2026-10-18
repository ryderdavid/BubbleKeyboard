import React from 'react';
import {interpolate, spring, useVideoConfig} from 'remotion';

/** Voicebar's light Material You look (the app's fallback palette). */
export const C = {
  page: '#EEF0F8',
  screen: '#F9F9FF',
  card: '#FFFFFF',
  barSurface: '#E1E2EC',
  key: '#DCE2F9',
  onKey: '#141B2C',
  pill: '#445E91',
  onPill: '#FFFFFF',
  center: '#D8E2FF',
  onCenter: '#001A41',
  command: '#6A4FA5',
  onCommand: '#FFFFFF',
  discard: '#BA1A1A',
  text: '#1A1B20',
  muted: '#44474F',
  bubbleMe: '#2B5C8A',
  bubbleThem: '#E4E8F3',
};

export const FONT = "'Roboto Flex', 'Roboto', 'Inter', sans-serif";

/** px per dp inside the phone screen. */
export const DP = 2.3;
export const dp = (v: number) => v * DP;

export const ease = (f: number, from: number, to: number, a = 0, b = 1) =>
  interpolate(f, [from, to], [a, b], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: (t) => 1 - Math.pow(1 - t, 3)});

export const Icon: React.FC<{name: 'mic' | 'keyboard' | 'cursor' | 'bksp' | 'more' | 'send' | 'check'; size: number; color: string}> = ({name, size, color}) => {
  const s = {width: size, height: size, display: 'block'};
  const st = {fill: 'none', stroke: color, strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const};
  switch (name) {
    case 'mic':
      return (<svg viewBox="0 0 24 24" style={s}><rect x="9" y="3" width="6" height="11" rx="3" fill={color} /><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21" {...st} /></svg>);
    case 'keyboard':
      return (<svg viewBox="0 0 24 24" style={s}><rect x="2.5" y="6" width="19" height="12" rx="2" {...st} />{[7, 10, 13, 16].map((x) => <circle key={x} cx={x} cy="10" r="0.9" fill={color} />)}{[7, 10, 13, 16].map((x) => <circle key={'b' + x} cx={x} cy="13" r="0.9" fill={color} />)}<path d="M8.5 15.5h7" {...st} /></svg>);
    case 'cursor':
      return (<svg viewBox="0 0 24 24" style={s}><path d="M9 4h6M9 20h6M12 4v16" {...st} /></svg>);
    case 'bksp':
      return (<svg viewBox="0 0 24 24" style={s}><path d="M8.5 5H20a1.5 1.5 0 0 1 1.5 1.5v11A1.5 1.5 0 0 1 20 19H8.5L2.5 12z" {...st} /><path d="M11.5 9.5l5 5M16.5 9.5l-5 5" {...st} /></svg>);
    case 'more':
      return (<svg viewBox="0 0 24 24" style={s}>{[6, 12, 18].map((y) => <circle key={y} cx="12" cy={y} r="1.9" fill={color} />)}</svg>);
    case 'send':
      return (<svg viewBox="0 0 24 24" style={s}><path d="M4 12l16-8-6 16-2.5-6.5z" {...st} /></svg>);
    case 'check':
      return (<svg viewBox="0 0 24 24" style={s}><path d="M5 12.5l4.5 4.5L19 7.5" {...st} strokeWidth={2.6} /></svg>);
  }
};

export type BarState =
  | {kind: 'rest'; press?: 'voice' | 'abc' | 'cursor' | null; ring?: 'voice' | 'abc' | 'cursor' | null}
  | {kind: 'talking'; text: string; seconds: number; swipe?: number; level?: number}
  | {kind: 'polishing'; t: number}
  | {kind: 'command'; said: string};

/** The bar: ⋮ · Cursor ABC Voice · ⌫, or the recording pill while talking. */
export const Bar: React.FC<{width: number; state: BarState; frame: number}> = ({width, state, frame}) => {
  const h = dp(52), b = dp(40), pad = dp(6), gap = dp(5);
  const y = (h - b) / 2;
  const pw = (width - 2 * pad - 2 * b - 4 * gap) / 3;
  const more = {x: pad, w: b}, bksp = {x: width - pad - b, w: b};
  const cur = {x: pad + b + gap, w: pw}, abc = {x: cur.x + pw + gap, w: pw}, voice = {x: abc.x + pw + gap, w: pw};
  const round = (x: number, w: number, bg: string, child: React.ReactNode, key: string, extra?: React.CSSProperties) => (
    <div key={key} style={{position: 'absolute', left: x, top: y, width: w, height: b, borderRadius: b / 2, background: bg, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: dp(6), ...extra}}>{child}</div>
  );
  const label = (t: string, c: string) => <span style={{font: `500 ${dp(14)}px ${FONT}`, color: c}}>{t}</span>;
  const items: React.ReactNode[] = [];
  items.push(round(more.x, more.w, 'transparent', <Icon name="more" size={dp(22)} color={C.onKey} />, 'more'));
  if (state.kind === 'rest') {
    const ring = (id: string) => state.ring === id ? {boxShadow: `0 0 0 ${dp(3) + dp(2) * (0.5 + 0.5 * Math.sin(frame / 5))}px ${C.command}88`} : {};
    const press = (id: string) => state.press === id ? {transform: 'scale(0.94)', filter: 'brightness(0.9)'} : {};
    items.push(round(cur.x, cur.w, C.key, <><Icon name="cursor" size={dp(20)} color={C.onKey} />{label('Cursor', C.onKey)}</>, 'cur', {...ring('cursor'), ...press('cursor')}));
    items.push(round(abc.x, abc.w, C.key, <><Icon name="keyboard" size={dp(22)} color={C.onKey} />{label('ABC', C.onKey)}</>, 'abc', {...ring('abc'), ...press('abc')}));
    items.push(round(voice.x, voice.w, C.pill, <><Icon name="mic" size={dp(20)} color={C.onPill} />{label('Voice', C.onPill)}</>, 'voice', {...ring('voice'), ...press('voice')}));
  } else {
    const span = {x: cur.x, w: voice.x + voice.w - cur.x};
    if (state.kind === 'talking') {
      const sw = state.swipe ?? 0;
      const level = state.level ?? 0.5;
      const dots = Array.from({length: 18}, (_, i) => {
        const v = 0.25 + 0.75 * Math.abs(Math.sin(frame / 3.2 + i * 0.9)) * level;
        return <div key={i} style={{width: dp(2.6), height: dp(4) + dp(14) * v, borderRadius: dp(2), background: C.onPill, opacity: 0.85}} />;
      });
      const shift = sw * span.w * 0.55;
      if (sw > 0.02) items.push(round(span.x, span.w, '#2E7D4F', <><Icon name="send" size={dp(20)} color="#fff" />{label('Send', '#fff')}</>, 'under', {justifyContent: 'flex-start', paddingLeft: dp(16)}));
      items.push(round(span.x + shift, span.w - shift, C.pill, (
        <div style={{display: 'flex', alignItems: 'center', width: '100%', padding: `0 ${dp(14)}px`, gap: dp(10), overflow: 'hidden'}}>
          {state.text ? <span style={{font: `500 ${dp(14)}px ${FONT}`, color: C.onPill, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', flexShrink: 1, direction: 'rtl', textAlign: 'left'}}>{'‎' + state.text}</span> : null}
          <div style={{display: 'flex', alignItems: 'center', gap: dp(2.2), flex: 1, justifyContent: 'center'}}>{dots}</div>
          <span style={{font: `500 ${dp(14)}px ${FONT}`, color: C.onPill, fontVariantNumeric: 'tabular-nums'}}>{`0:${String(Math.floor(state.seconds)).padStart(2, '0')}`}</span>
        </div>
      ), 'talk'));
    } else if (state.kind === 'polishing') {
      const p = (state.t % 1.4) / 1.4, s = p * p * (3 - 2 * p);
      items.push(round(span.x, span.w, C.center, (
        <>
          <div style={{position: 'absolute', inset: 0, borderRadius: b / 2, overflow: 'hidden'}}>
            <div style={{position: 'absolute', top: 0, bottom: 0, width: '45%', left: `${-45 + 145 * s}%`, background: `linear-gradient(90deg, transparent, ${C.onCenter}38, transparent)`}} />
          </div>
          <span style={{position: 'relative', font: `500 ${dp(14)}px ${FONT}`, color: C.onCenter}}>Polishing…</span>
        </>
      ), 'pol'));
    } else {
      items.push(round(span.x, span.w, C.command, label(state.said ? `Voice bar: ${state.said}` : 'Voice bar…', C.onCommand), 'cmd', {boxShadow: `0 0 ${dp(10)}px ${C.command}66`}));
    }
  }
  items.push(round(bksp.x, bksp.w, C.key, <Icon name="bksp" size={dp(22)} color={C.onKey} />, 'bksp'));
  return <div style={{position: 'relative', width, height: h, background: C.barSurface, borderTop: `1px solid #0000000f`}}>{items}</div>;
};

/** Where the bar's pills are, for fingers and tips (relative to the bar's left). */
export const barSpots = (width: number) => {
  const b = dp(40), pad = dp(6), gap = dp(5);
  const pw = (width - 2 * pad - 2 * b - 4 * gap) / 3;
  const cx = (x: number, w: number) => x + w / 2;
  const cur = pad + b + gap;
  return {cursor: cx(cur, pw), abc: cx(cur + pw + gap, pw), voice: cx(cur + 2 * (pw + gap), pw), spanLeft: cur, spanRight: cur + 3 * pw + 2 * gap, cy: dp(26)};
};

/** A fingertip: fades in, presses (ripple), lifts. */
export const Finger: React.FC<{x: number; y: number; frame: number; at: number; hold?: number; dx?: number}> = ({x, y, frame, at, hold = 8, dx = 0}) => {
  const {fps} = useVideoConfig();
  const t = frame - at;
  if (t < -10 || t > hold + 22) return null;
  const appear = ease(t, -10, 0);
  const leave = 1 - ease(t, hold + 8, hold + 22);
  const press = spring({frame: t, fps, config: {damping: 14}});
  const slide = dx * ease(t, 2, hold);
  const ripple = Math.max(0, Math.min(1, (t - 0) / 14));
  return (
    <div style={{position: 'absolute', left: x + slide, top: y, pointerEvents: 'none'}}>
      <div style={{position: 'absolute', left: -70 * ripple, top: -70 * ripple, width: 140 * ripple, height: 140 * ripple, borderRadius: '50%', border: `4px solid ${C.pill}`, opacity: (1 - ripple) * 0.6 * (t >= 0 ? 1 : 0)}} />
      <div style={{position: 'absolute', left: -38, top: -38, width: 76, height: 76, borderRadius: '50%', background: '#ffffff', boxShadow: '0 6px 24px #0003', opacity: 0.92 * appear * leave, transform: `scale(${1.15 - 0.15 * press})`, border: '3px solid #0000001a'}} />
    </div>
  );
};
