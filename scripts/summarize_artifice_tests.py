"""Summarize native Artifice evidence without treating captures as visual passes."""
import argparse
import json
from pathlib import Path


def summarize(path):
    report = json.loads(path.read_text(encoding='utf-8'))
    frames = []
    for capture in report.get('captures', []):
        probe = capture.get('renderProbe', {})
        textures = {}
        invalid = 0
        for hand in probe.get('hands', []):
            for texture, counts in hand.get('textures', {}).items():
                textures[texture] = textures.get(texture, 0) + counts['vertices']
                invalid += counts['invalidVertices']
        accessories = {texture: vertices for texture, vertices in textures.items()
                       if any(namespace in texture for namespace in ('artifacts:', 'aether:', 'deep_aether:'))}
        poses = probe.get('emfPoses', [])
        physical = capture.get('physicalAccessoryProbe', {})
        face_vertices = {key: value for key, value in physical.get('vertices', {}).items()
                         if any(texture in key for texture in ('whoopee_cushion', 'snorkel', 'running_shoes'))}
        frames.append(dict(case=capture['case'], name=capture['name'], view=capture['view'],
                           screenshot=capture['screenshot'], nativeHands=len(probe.get('hands', [])),
                           accessoryVertices=sum(accessories.values()), accessoryTextures=accessories,
                           invalidHandVertices=invalid, emfEvaluations=len(poses),
                           actualCamera=capture.get('actualCameraType', capture['view']['camera']),
                           physicalAccessoryCalls=physical.get('calls'), faceAndShoesVertices=face_vertices,
                           actualAccessoryInventory=capture.get('actualAccessoryInventory'),
                           armRotationScaleError=max((pose['rotationScaleError'] for pose in poses), default=None),
                           armPositionError=max((pose['torsoAdjustedPositionError'] for pose in poses), default=None)))
    return dict(source=str(path.resolve()), status=report['status'], detail=report.get('detail'),
                plannedContexts=report.get('plannedContexts'), completedContexts=report.get('completedContexts'),
                capturedFrames=len(frames), nativeChecks=[dict(view=case['view'], status=case['status'],
                    checks={key: value for key, value in case.items() if key.endswith(('Verified', 'Observed', 'Distance'))})
                    for case in report.get('cases', [])],
                restoration={key: value for key, value in report.items() if key.endswith(('Restored', 'Cleaned'))},
                visualReview='REQUIRED: this report records native state and emitted geometry, not visual alignment.',
                frames=frames)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = summarize(args.report)
    content = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding='utf-8')
        print(args.output)
    else:
        print(content)
