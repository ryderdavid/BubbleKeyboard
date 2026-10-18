import React from 'react';
import {Composition} from 'remotion';
import {Welcome, totalFrames, FPS} from './Video';
export const Root: React.FC = () => (
  <Composition id="Welcome" component={Welcome} durationInFrames={totalFrames} fps={FPS} width={1080} height={1920} />
);
