import React from 'react';
import {interpolate} from 'remotion';

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
