import type {CSSProperties, ReactNode} from 'react';

export const CameraRig = ({children, yaw = 0, pitch = 0, scale = 1, x = 0, y = 0, style}: {
  children: ReactNode; yaw?: number; pitch?: number; scale?: number; x?: number; y?: number; style?: CSSProperties;
}) => <div data-v4-camera style={{position: 'absolute', inset: 0, perspective: 1600, ...style}}>
  <div style={{position: 'absolute', inset: 0, transformStyle: 'preserve-3d', transformOrigin: '50% 50%',
    transform: `translate3d(${x}px,${y}px,0) rotateX(${pitch}deg) rotateY(${yaw}deg) scale(${scale})`}}>{children}</div>
</div>;
