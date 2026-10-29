import {AbsoluteFill, Composition, Sequence, registerRoot, useVideoConfig} from 'remotion';
import {HookScene} from './scenes/HookScene';
import {PathScene} from './scenes/PathScene';
import {ScaleScene} from './scenes/ScaleScene';
import {ShellScene} from './scenes/ShellScene';
import {EcosystemScene} from './scenes/EcosystemScene';
import {DepthScene} from './scenes/DepthScene';
import {EndScene} from './scenes/EndScene';
import {film} from './data';
import {theme} from './theme';
import {V4Promo} from './v4/V4Promo';
import {v4Film} from './v4/data';
import {V5Promo} from './v5/V5Promo';
import {v5Film} from './v5/data';
import './fonts';

const scenes = [HookScene, PathScene, ScaleScene, ShellScene, EcosystemScene, DepthScene, EndScene];

export const DSHMobilePromo = () => {
  const {width, height} = useVideoConfig();
  const format = height > width ? 'portrait' : 'landscape';
  return <AbsoluteFill style={{background: theme.colors.bg}}>
    {film.shots.map((shot, index) => {
      const Scene = scenes[index];
      return <Sequence key={shot.id} name={shot.id} from={shot.start * film.fps}
        durationInFrames={shot.seconds * film.fps}>
        <Scene />
      </Sequence>;
    })}
    <div data-format={format} />
  </AbsoluteFill>;
};

export const RemotionRoot = () => <>
  <Composition id="DSHMobilePortrait" component={DSHMobilePromo} durationInFrames={film.seconds * film.fps}
    fps={film.fps} width={film.portrait.width} height={film.portrait.height} />
  <Composition id="DSHMobileLandscape" component={DSHMobilePromo} durationInFrames={film.seconds * film.fps}
    fps={film.fps} width={film.landscape.width} height={film.landscape.height} />
  <Composition id="DSHMobilePortrait-v4" component={V4Promo} durationInFrames={v4Film.durationInFrames}
    fps={v4Film.fps} width={1080} height={1920} />
  <Composition id="DSHMobileLandscape-v4" component={V4Promo} durationInFrames={v4Film.durationInFrames}
    fps={v4Film.fps} width={1920} height={1080} />
  <Composition id="DSHMobilePortrait-v5" component={V5Promo} durationInFrames={v5Film.durationInFrames}
    fps={v5Film.fps} width={1080} height={1920} />
  <Composition id="DSHMobileLandscape-v5" component={V5Promo} durationInFrames={v5Film.durationInFrames}
    fps={v5Film.fps} width={1920} height={1080} />
</>;

// Both src/index.tsx and the legacy src/Root.tsx are supported CLI entry points.
registerRoot(RemotionRoot);
