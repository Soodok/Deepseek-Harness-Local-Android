import {AbsoluteFill, Sequence, useCurrentFrame} from 'remotion';
import {v5Film} from './data';
import {C} from './components/Studio';
import {IntroScene, PerformanceScene} from './scenes/Opening';
import {ExtensionsScene, PluginsScene} from './scenes/Ecosystem';
import {AccessibilityScene, OCRScene} from './scenes/Control';
import {SizeScene, WebUIScene} from './scenes/Workspace';
import {EndScene, PetScene} from './scenes/Companion';

const scenes = {intro: IntroScene, performance: PerformanceScene, extensions: ExtensionsScene, plugins: PluginsScene,
  accessibility: AccessibilityScene, ocr: OCRScene, webui: WebUIScene, size: SizeScene, pet: PetScene, end: EndScene} as const;

// Scenes never share a frame. A short graphic shutter masks the edit without crossfading content.
const CutShutter = ({duration, first}: {duration: number; first: boolean}) => {
  const frame = useCurrentFrame();
  const entering = !first && frame < 9;
  const exiting = frame >= duration - 6;
  if (!entering && !exiting) return null;
  const extent = entering ? 1 - frame / 9 : (frame - duration + 6) / 6;
  return <AbsoluteFill style={{pointerEvents: 'none', zIndex: 30, clipPath: `inset(0 ${100 - extent * 100}% 0 0)`,
    background: 'linear-gradient(100deg,#0D1828,#1F3F5C 90%,#7DD3FC)', opacity: .97}} />;
};

export const V5Promo = () => <AbsoluteFill style={{backgroundColor: C.bg}}>
  {v5Film.shots.map((shot, i) => {
    const Scene = scenes[shot.id];
    return <Sequence key={shot.id} name={`v5-${shot.id}`} from={shot.from} durationInFrames={shot.duration}>
      <Scene/><CutShutter duration={shot.duration} first={i === 0}/>
    </Sequence>;
  })}
</AbsoluteFill>;
