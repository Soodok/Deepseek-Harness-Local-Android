import {AbsoluteFill, Sequence} from 'remotion';
import {theme} from '../theme';
import {v4Film} from './data';
import {IntroScene} from './scenes/IntroScene';
import {DevicesScene} from './scenes/DevicesScene';
import {ToolsScene} from './scenes/ToolsScene';
import {InstallScene} from './scenes/InstallScene';
import {VerifyScene} from './scenes/VerifyScene';
import {PerceptionScene} from './scenes/PerceptionScene';
import {WebUIScene} from './scenes/WebUIScene';
import {NativeScene} from './scenes/NativeScene';
import {VolumeScene} from './scenes/VolumeScene';
import {CommunityScene} from './scenes/CommunityScene';
import {EndV4Scene} from './scenes/EndV4Scene';

const studies = {intro: IntroScene, devices: DevicesScene, tools: ToolsScene, install: InstallScene, verify: VerifyScene,
  perception: PerceptionScene, webui: WebUIScene, native: NativeScene, volume: VolumeScene, community: CommunityScene, end: EndV4Scene} as const;

export const V4Promo = () => <AbsoluteFill style={{backgroundColor: theme.colors.bg}}>
  {v4Film.shots.map((shot) => {
    const Scene = studies[shot.id];
    return <Sequence key={shot.id} name={`v4-${shot.id}`} from={shot.from} durationInFrames={shot.duration}><Scene /></Sequence>;
  })}
</AbsoluteFill>;
