import React from 'react';
import {AbsoluteFill, Audio, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import '@fontsource/roboto-flex';
import timeline from './timeline.json';
import {Bar, BarState, C, DP, FONT, Finger, Icon, barSpots, dp, ease} from './ui';

type Word = {w: string; start: number; end: number};
type Scene = {id: string; title: string; text: string; audio: string | null; lead: number; frames: number; words: Word[]};
const scenes = timeline as Scene[];

export const FPS = 30;
export const totalFrames = scenes.reduce((a, s) => a + s.frames, 0);

// the phone screen inside the video
const SX = 70, SY = 520, SW = 940, SH = 1330, BAR_H = dp(52);
const BAR_Y = SH - BAR_H;

/** Frame (within the scene) when the narration reaches the first word starting with [w], after [after]. */
const at = (s: Scene, w: string, after = 0) => {
  const fps = FPS;
  const i = s.words.findIndex((x, k) => k >= after && x.w.toLowerCase().replace(/[^a-z']/g, '').startsWith(w.toLowerCase()));
  return Math.round((s.lead + (i >= 0 ? s.words[i].start : 0)) * fps);
};

const Caption: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const t = f / FPS - s.lead;
  return (
    <div style={{position: 'absolute', left: 80, right: 80, top: 250, font: `400 46px/1.32 ${FONT}`, color: C.text}}>
      {s.words.map((w, i) => {
        const on = ease(t, w.start - 0.05, w.start + 0.12, 0.28, 1);
        return <span key={i} style={{opacity: on}}>{w.w} </span>;
      })}
    </div>
  );
};

const Title: React.FC<{s: Scene; f: number}> = ({s, f}) => (
  <div style={{position: 'absolute', left: 80, right: 80, top: 120, font: `700 76px ${FONT}`, color: C.text, letterSpacing: -1,
    opacity: ease(f, 0, 10), transform: `translateY(${(1 - ease(f, 0, 14)) * 24}px)`}}>{s.title}</div>
);

/** The chat app the bar types into. */
const Chat: React.FC<{field: React.ReactNode; sent?: string[]; extra?: React.ReactNode; fieldBottom?: number}> = ({field, sent = [], extra, fieldBottom = BAR_H + 26}) => (
  <>
    <div style={{position: 'absolute', left: 0, right: 0, top: 0, height: 140, background: '#E8ECF7', display: 'flex', alignItems: 'center', padding: '0 40px', gap: 24}}>
      <div style={{width: 76, height: 76, borderRadius: 38, background: '#B9C7E6', display: 'flex', alignItems: 'center', justifyContent: 'center', font: `600 34px ${FONT}`, color: '#24324F'}}>J</div>
      <div style={{font: `500 40px ${FONT}`, color: C.text}}>Jamie</div>
    </div>
    <div style={{position: 'absolute', left: 36, right: 36, bottom: fieldBottom + 120, display: 'flex', flexDirection: 'column', gap: 20}}>
      <div style={{alignSelf: 'flex-start', maxWidth: '75%', background: C.bubbleThem, borderRadius: 40, padding: '22px 32px', font: `400 38px/1.3 ${FONT}`, color: C.text}}>Hike tomorrow? When should we meet?</div>
      {sent.map((m, i) => (
        <div key={i} style={{alignSelf: 'flex-end', maxWidth: '78%', background: C.bubbleMe, borderRadius: 40, padding: '22px 32px', font: `400 38px/1.3 ${FONT}`, color: '#fff'}}>{m}</div>
      ))}
    </div>
    <div style={{position: 'absolute', left: 30, right: 30, bottom: fieldBottom, minHeight: 100, borderRadius: 50, background: '#E9EDF7', display: 'flex', alignItems: 'center', padding: '18px 36px', gap: 20}}>
      <div style={{flex: 1, font: `400 38px/1.3 ${FONT}`, color: C.text}}>{field}</div>
      <div style={{width: 80, height: 80, borderRadius: 40, background: C.bubbleMe, display: 'flex', alignItems: 'center', justifyContent: 'center'}}><Icon name="send" size={40} color="#fff" /></div>
    </div>
    {extra}
  </>
);

const Caret: React.FC<{f: number}> = ({f}) => <span style={{display: 'inline-block', width: 3, height: 44, background: C.pill, verticalAlign: 'middle', marginLeft: 2, opacity: Math.floor(f / 15) % 2 ? 0.2 : 1}} />;

/** Words appearing one after another between frames [from] and [to]. */
const typed = (text: string, f: number, from: number, to: number) => {
  const ws = text.split(' ');
  const n = Math.floor(interpolate(f, [from, to], [0, ws.length], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'}));
  return ws.slice(0, n).join(' ');
};

const Screen: React.FC<{children: React.ReactNode; f: number; enter?: boolean}> = ({children, f, enter}) => {
  const {fps} = useVideoConfig();
  const s = enter ? spring({frame: f - 4, fps, config: {damping: 18}}) : 1;
  return (
    <div style={{position: 'absolute', left: SX, top: SY, width: SW, height: SH, borderRadius: 64, overflow: 'hidden', background: C.screen,
      boxShadow: '0 30px 80px #1b2a4a2e, 0 0 0 10px #ffffff', transform: `translateY(${(1 - s) * 140}px)`, opacity: s}}>
      {children}
    </div>
  );
};

const BarAt: React.FC<{state: BarState; f: number; y?: number}> = ({state, f, y = 0}) => (
  <div style={{position: 'absolute', left: 0, top: BAR_Y + y}}><Bar width={SW} state={state} frame={f} /></div>
);

const spots = barSpots(SW);
const RAW = 'so um lets meet at the trailhead at like nine';

/* ---------------------------------------------------------------- scenes */

const Intro: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const {fps} = useVideoConfig();
  const rise = spring({frame: f - at(s, 'slim'), fps, config: {damping: 16}});
  const glow = ease(f, at(s, 'bottom'), at(s, 'bottom') + 10) * (1 - ease(f, s.frames - 30, s.frames - 10));
  return (
    <Screen f={f} enter>
      <Chat field={<span style={{color: C.muted}}>Message</span>} />
      <div style={{position: 'absolute', left: 0, top: BAR_Y + (1 - rise) * BAR_H * 1.4, boxShadow: `0 0 0 ${8 * glow}px ${C.command}55`}}><Bar width={SW} state={{kind: 'rest'}} frame={f} /></div>
      <Finger x={spots.voice} y={BAR_Y + spots.cy} frame={f} at={at(s, 'thumb')} hold={14} />
    </Screen>
  );
};

const Talk: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const tap = at(s, 'tap') + 4;
  const talking = f >= tap + 4;
  const text = typed(RAW, f, at(s, 'start'), s.frames - 8);
  const state: BarState = talking ? {kind: 'talking', text: '', seconds: (f - tap) / FPS, level: 0.8} : {kind: 'rest', press: f >= tap - 2 && f < tap + 4 ? 'voice' : null, ring: f < tap ? 'voice' : null};
  return (
    <Screen f={f}>
      <Chat field={talking ? <span style={{textDecoration: 'underline', textDecorationColor: '#0004'}}>{text}<Caret f={f} /></span> : <span style={{color: C.muted}}>Message</span>} />
      <BarAt state={state} f={f} />
      <Finger x={spots.voice} y={BAR_Y + spots.cy} frame={f} at={tap} />
    </Screen>
  );
};

/** The raw words, then the fillers shrinking away and the sentence tidied. */
const Tidy: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const pause = at(s, 'pause');
  const polishStart = at(s, 'cleaned');
  const polishEnd = polishStart + 34;
  const clean = ease(f, polishEnd - 6, polishEnd + 14);
  const tokens: {raw: string; clean: string; filler?: boolean}[] = [
    {raw: 'so', clean: 'So,'}, {raw: 'um', clean: '', filler: true}, {raw: 'lets', clean: 'let’s'}, {raw: 'meet', clean: 'meet'},
    {raw: 'at', clean: 'at'}, {raw: 'the', clean: 'the'}, {raw: 'trailhead', clean: 'trailhead'}, {raw: 'at', clean: 'at'},
    {raw: 'like', clean: '', filler: true}, {raw: 'nine', clean: 'nine.'},
  ];
  const field = (
    <span>
      {tokens.map((t, i) => t.filler ? (
        <span key={i} style={{display: 'inline-block', overflow: 'hidden', whiteSpace: 'pre', verticalAlign: 'bottom', maxWidth: `${(1 - clean) * 200}px`, color: clean > 0 ? C.discard : C.text, opacity: 1 - clean, textDecoration: clean > 0 ? 'line-through' : 'none'}}>{t.raw + ' '}</span>
      ) : (
        <span key={i}>{clean > 0.5 ? t.clean : t.raw}{' '}</span>
      ))}
      {clean < 0.5 ? <Caret f={f} /> : null}
    </span>
  );
  const state: BarState = f >= polishStart && f < polishEnd ? {kind: 'polishing', t: (f - polishStart) / FPS}
    : {kind: 'talking', text: '', seconds: 4 + f / FPS, level: f < pause ? 0.8 : 0.08};
  const hl = clean * (1 - ease(f, polishEnd + 40, polishEnd + 60));
  return (
    <Screen f={f}>
      <Chat field={<span style={{background: `rgba(106,79,165,${0.14 * hl})`, borderRadius: 12}}>{field}</span>} />
      <BarAt state={state} f={f} />
    </Screen>
  );
};

const CLEAN = 'So, let’s meet at the trailhead at nine.';

const Finish: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const tap = at(s, 'tap') + 6;
  const swipeAt = at(s, 'swipe') + 4;
  const sendAt = swipeAt + 26;
  let state: BarState;
  let field: React.ReactNode = <span>{CLEAN}<Caret f={f} /></span>;
  const sent: string[] = [];
  if (f < tap) state = {kind: 'talking', text: '', seconds: 9 + f / FPS, level: 0.1};
  else if (f < tap + 24) state = {kind: 'polishing', t: (f - tap) / FPS};
  else if (f < swipeAt - 14) state = {kind: 'rest'};
  else if (f < sendAt) {
    const sw = ease(f, swipeAt, sendAt - 4);
    state = {kind: 'talking', text: '', seconds: 2 + (f - swipeAt + 14) / FPS, level: 0.3, swipe: sw};
    field = <span>{CLEAN} See you there!<Caret f={f} /></span>;
  } else {
    state = {kind: 'rest'};
    field = <span style={{color: C.muted}}>Message</span>;
    sent.push(CLEAN + ' See you there!');
  }
  return (
    <Screen f={f}>
      <Chat field={field} sent={sent} />
      <BarAt state={state} f={f} />
      <Finger x={(spots.spanLeft + spots.spanRight) / 2} y={BAR_Y + spots.cy} frame={f} at={tap} />
      <Finger x={spots.spanLeft + 120} y={BAR_Y + spots.cy} frame={f} at={swipeAt} hold={20} dx={520} />
      {f >= swipeAt - 6 && f < sendAt + 30 ? (
        <div style={{position: 'absolute', left: 0, right: 0, top: BAR_Y - 74, display: 'flex', justifyContent: 'space-between', padding: '0 60px', font: `500 30px ${FONT}`, color: C.muted, opacity: ease(f, swipeAt - 6, swipeAt + 4) * (1 - ease(f, sendAt + 10, sendAt + 30))}}>
          <span style={{color: C.discard}}>← throw away</span><span style={{color: '#2E7D4F'}}>finish and send →</span>
        </div>
      ) : null}
    </Screen>
  );
};

/** Gboard, as a sketch: a toolbar row with Voicebar's button over its mic key, and rows of keys. */
const Gboard: React.FC<{f: number; press: boolean}> = ({f, press}) => {
  const rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
  const pulse = 0.5 + 0.5 * Math.sin(f / 5);
  return (
    <div style={{width: SW, height: 560, background: '#E3E6EE', paddingTop: 16}}>
      <div style={{height: 96, display: 'flex', alignItems: 'center', padding: '0 28px', gap: 44}}>
        {['⊞', '☺', '⚙', '🎨'].map((c, i) => <div key={i} style={{width: 56, height: 56, borderRadius: 28, background: '#D3D7E1', opacity: 0.8}} />)}
        <div style={{flex: 1}} />
        <div style={{width: 84, height: 84, borderRadius: 42, background: C.pill, display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: `0 0 0 ${6 + 6 * pulse}px ${C.command}55`, transform: press ? 'scale(0.9)' : 'none'}}><Icon name="mic" size={44} color="#fff" /></div>
      </div>
      {rows.map((r, ri) => (
        <div key={ri} style={{display: 'flex', justifyContent: 'center', gap: 12, marginTop: 14}}>
          {r.split('').map((k) => <div key={k} style={{width: 78, height: 96, borderRadius: 14, background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', font: `400 40px ${FONT}`, color: C.text}}>{k}</div>)}
        </div>
      ))}
    </div>
  );
};

const Type: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const tapAbc = at(s, 'abc') + 2;
  const tapBack = at(s, 'brings') - 2;
  const up = ease(f, tapAbc + 4, tapAbc + 16) * (1 - ease(f, tapBack + 6, tapBack + 18));
  const gbH = 560;
  return (
    <Screen f={f}>
      <Chat field={<span>{up > 0.5 ? 'Bringing snacks' : ''}<Caret f={f} /></span>} fieldBottom={BAR_H + 26 + up * (gbH - BAR_H)} />
      <BarAt state={{kind: 'rest', press: f >= tapAbc - 2 && f < tapAbc + 4 ? 'abc' : null, ring: f < tapAbc ? 'abc' : null}} f={f} y={up * BAR_H * 1.5} />
      <div style={{position: 'absolute', left: 0, top: SH - gbH * up, opacity: up > 0 ? 1 : 0}}><Gboard f={f} press={f >= tapBack - 2 && f < tapBack + 6} /></div>
      <Finger x={spots.abc} y={BAR_Y + spots.cy} frame={f} at={tapAbc} />
      <Finger x={SW - 28 - 42} y={SH - gbH + 16 + 48} frame={f} at={tapBack} />
    </Screen>
  );
};

const Cursor: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const tap = at(s, 'cursor') + 2;
  const open = ease(f, tap + 4, tap + 14);
  const sel = ease(f, at(s, 'selecting'), at(s, 'selecting') + 10);
  const copy = at(s, 'copying');
  const text = 'Meet at the trailhead at nine.';
  const words = text.split(' ');
  const drawerH = 470;
  return (
    <Screen f={f}>
      <Chat field={<span>{words.map((w, i) => <span key={i} style={{background: i === 3 ? `rgba(68,94,145,${0.3 * sel})` : 'transparent', borderRadius: 6}}>{w}{' '}</span>)}</span>} fieldBottom={BAR_H + 26 + open * drawerH} />
      <div style={{position: 'absolute', left: 16, right: 16, top: BAR_Y - drawerH * open, height: drawerH, borderRadius: 48, background: '#E7EAF4', opacity: open, padding: 28, boxSizing: 'border-box'}}>
        <div style={{display: 'flex', gap: 14}}>
          {['Select all', 'Cut', 'Copy', 'Paste'].map((c) => (
            <div key={c} style={{flex: 1, height: 84, borderRadius: 42, background: c === 'Copy' && f >= copy && f < copy + 12 ? C.pill : C.key, color: c === 'Copy' && f >= copy && f < copy + 12 ? '#fff' : C.onKey, display: 'flex', alignItems: 'center', justifyContent: 'center', font: `500 32px ${FONT}`}}>{c}</div>
          ))}
        </div>
        <div style={{marginTop: 26, height: 300, borderRadius: 36, background: '#DCE0EC', display: 'flex', alignItems: 'center', justifyContent: 'center', font: `400 30px ${FONT}`, color: C.muted}}>slide to move the cursor</div>
      </div>
      <BarAt state={{kind: 'rest', press: f >= tap - 2 && f < tap + 4 ? 'cursor' : null, ring: f < tap ? 'cursor' : null}} f={f} />
      <Finger x={spots.cursor} y={BAR_Y + spots.cy} frame={f} at={tap} />
      <Finger x={300} y={BAR_Y - drawerH + 28 + 84 + 26 + 150} frame={f} at={tap + 20} hold={Math.max(10, at(s, 'selecting') - tap - 10)} dx={340} />
      <Finger x={16 + 28 + 2.5 * (SW - 32 - 56 - 42) / 4 + 21} y={BAR_Y - drawerH + 28 + 42} frame={f} at={copy} />
    </Screen>
  );
};

const Commands: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const vb = at(s, 'voice');
  const send = at(s, 'send');
  const sentAt = send + 18;
  let state: BarState = {kind: 'talking', text: '', seconds: 3 + f / FPS, level: 0.7};
  if (f >= vb && f < sentAt) state = {kind: 'command', said: f >= send ? 'send' : ''};
  if (f >= sentAt) state = {kind: 'rest'};
  const msg = 'See you at nine!';
  return (
    <Screen f={f}>
      <Chat field={f < sentAt ? <span>{msg}<Caret f={f} /></span> : <span style={{color: C.muted}}>Message</span>} sent={f >= sentAt ? [msg] : []} />
      <BarAt state={state} f={f} />
    </Screen>
  );
};

const Apps = ['Messages', 'Photos', 'Maps', 'Music', 'Clock', 'Camera', 'Calendar', 'Notes', 'Weather', 'Files', 'Contacts', 'Settings'];
const AppColors = ['#3D7BD9', '#E0745A', '#3FA06A', '#B354C8', '#5A6478', '#2F3A4F', '#D9534F', '#E3B341', '#4AA3DF', '#6C8CD5', '#7A8B99', '#68707E'];

const HandsFree: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const start = at(s, 'hands');
  const tapAt = at(s, 'tap');
  const scrollAt = at(s, 'scroll');
  const openAt = at(s, 'open');
  const on = ease(f, start, start + 12);
  // the cursor moves to Photos, taps, then the screen scrolls
  const target = {x: 80 + 1 * 210 + 85, y: 260 + 85};
  const cx = interpolate(f, [start, tapAt], [SW / 2, target.x], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const cy = interpolate(f, [start, tapAt], [SH / 2, target.y], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const scroll = ease(f, scrollAt, scrollAt + 20) * -260 * (1 - ease(f, openAt, openAt + 1));
  const said = f >= openAt ? '“open Maps”' : f >= scrollAt ? '“scroll down”' : f >= tapAt ? '“tap Photos”' : '';
  return (
    <Screen f={f}>
      <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(160deg, #DDE5F7, #F3E8F4)'}} />
      <div style={{position: 'absolute', left: 80, top: 260 + scroll, display: 'grid', gridTemplateColumns: 'repeat(4, 170px)', gap: '70px 40px'}}>
        {Apps.concat(Apps).map((a, i) => (
          <div key={i} style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 14}}>
            <div style={{width: 150, height: 150, borderRadius: 44, background: AppColors[i % 12], transform: i === 1 && f >= tapAt && f < tapAt + 8 ? 'scale(0.9)' : 'none'}} />
            <div style={{font: `400 30px ${FONT}`, color: C.text}}>{a}</div>
          </div>
        ))}
      </div>
      {/* the hands-free pill in the status bar */}
      <div style={{position: 'absolute', top: 36, left: '50%', transform: `translateX(-50%) scale(${0.9 + 0.1 * on})`, opacity: on, display: 'flex', alignItems: 'center', gap: 14, background: C.command, color: '#fff', padding: '14px 30px', borderRadius: 40, font: `500 32px ${FONT}`}}>
        <Icon name="mic" size={34} color="#fff" />Hands-free {said ? `· ${said}` : '· listening'}
      </div>
      {/* the cursor */}
      <div style={{position: 'absolute', left: cx, top: cy, opacity: on}}>
        <svg width="110" height="132" viewBox="0 0 20 24" style={{filter: 'drop-shadow(0 4px 8px #0004)'}}><path d="M2 2l15 9-7 1.5L6.5 20z" fill={C.command} stroke="#fff" strokeWidth="1.6" strokeLinejoin="round" /></svg>
        {f >= tapAt && f < tapAt + 16 ? <div style={{position: 'absolute', left: -50, top: -50, width: 100 + (f - tapAt) * 6, height: 100 + (f - tapAt) * 6, marginLeft: -(f - tapAt) * 3, marginTop: -(f - tapAt) * 3, borderRadius: '50%', border: `5px solid ${C.command}`, opacity: 1 - (f - tapAt) / 16}} /> : null}
      </div>
      {f >= openAt ? (
        <div style={{position: 'absolute', inset: 0, background: '#E7F1E9', opacity: ease(f, openAt, openAt + 10), display: 'flex', alignItems: 'center', justifyContent: 'center', font: `500 56px ${FONT}`, color: '#2C5E3C'}}>Maps</div>
      ) : null}
    </Screen>
  );
};

const Outro: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const how = at(s, 'how');
  const hl = ease(f, how, how + 10);
  const logo = ease(f, at(s, 'happy') - 6, at(s, 'happy') + 10);
  return (
    <Screen f={f}>
      <div style={{position: 'absolute', inset: 0, background: '#F2F3FA', padding: '70px 40px'}}>
        <div style={{font: `400 64px ${FONT}`, color: C.text, marginBottom: 50, opacity: 1 - logo}}>Voicebar</div>
        {[['Bar and Gboard', 'Layout, colors, the way back'], ['Voice typing', 'ElevenLabs, Parakeet, cleanup'], ['Dictionary', 'Names and words to get right'], ['How to use it', 'Watch the welcome video again'], ['Try it', 'A place to practice']].map(([t, sub], i) => (
          <div key={t} style={{background: i === 3 ? `rgba(216,226,255,${0.4 + 0.6 * hl})` : '#fff', borderRadius: i === 0 ? '44px 44px 10px 10px' : i === 4 ? '10px 10px 44px 44px' : 10, marginBottom: 6, padding: '34px 40px', opacity: 1 - logo,
            boxShadow: i === 3 ? `0 0 0 ${6 * hl}px ${C.pill}55` : 'none', transform: i === 3 ? `scale(${1 + 0.03 * hl})` : 'none'}}>
            <div style={{font: `500 40px ${FONT}`, color: C.text}}>{t}</div>
            <div style={{font: `400 32px ${FONT}`, color: C.muted, marginTop: 6}}>{sub}</div>
          </div>
        ))}
      </div>
      <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 50, opacity: logo, background: C.screen}}>
        <div style={{width: 200, height: 200, borderRadius: 100, background: C.pill, display: 'flex', alignItems: 'center', justifyContent: 'center', transform: `scale(${0.8 + 0.2 * logo})`}}><Icon name="mic" size={110} color="#fff" /></div>
        <div style={{font: `700 96px ${FONT}`, color: C.text, letterSpacing: -2}}>Voicebar</div>
      </div>
      <div style={{position: 'absolute', left: 0, top: BAR_Y, opacity: logo}}><Bar width={SW} state={{kind: 'rest'}} frame={f} /></div>
    </Screen>
  );
};

const Views: Record<string, React.FC<{s: Scene; f: number}>> = {intro: Intro, talk: Talk, tidy: Tidy, finish: Finish, type: Type, cursor: Cursor, commands: Commands, handsfree: HandsFree, outro: Outro};

const SceneView: React.FC<{s: Scene}> = ({s}) => {
  const f = useCurrentFrame();
  const V = Views[s.id];
  const fadeOut = 1 - ease(f, s.frames - 8, s.frames);
  return (
    <AbsoluteFill style={{opacity: s.id === 'outro' ? 1 : fadeOut}}>
      <Title s={s} f={f} />
      <Caption s={s} f={f} />
      <V s={s} f={f} />
      {s.audio ? <Sequence from={Math.round(s.lead * FPS)}><Audio src={staticFile(s.audio)} /></Sequence> : null}
    </AbsoluteFill>
  );
};

export const Welcome: React.FC = () => {
  let from = 0;
  return (
    <AbsoluteFill style={{background: `linear-gradient(180deg, ${C.page}, #E4E8F6)`, fontFamily: FONT}}>
      {scenes.map((s) => {
        const seq = <Sequence key={s.id} from={from} durationInFrames={s.frames}><SceneView s={s} /></Sequence>;
        from += s.frames;
        return seq;
      })}
    </AbsoluteFill>
  );
};

export const _unused = DP;
