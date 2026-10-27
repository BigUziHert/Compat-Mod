"""Extract actual recorded arm cube geometry/UVs; does not assign visual grades."""
from pathlib import Path
import argparse,json,hashlib,itertools

p=argparse.ArgumentParser();p.add_argument('report',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
d=json.loads(a.report.read_text());rows=[]
def bounds(vertices):
    return [min(v[i] for v in vertices) for i in range(3)]+[max(v[i] for v in vertices) for i in range(3)]
def world(m,v):return [sum(m[col*4+row]*v[col]/16 for col in range(3))+m[12+row] for row in range(3)]
for c in d.get('captures',[]):
    probe=c.get('nativePose',{}).get('armorMesh',{})
    events=[e for e in probe.get('events',[]) if e['stage']=='compile_entry' and not e['shadow'] and not e['isInHandItemLayerTransform']]
    for side in ['leftArm','rightArm']:
        def last(path):
            q=[e for e in events if e['path']==path];return q[-1] if q else None
        skin=last('skin/'+side+'/EMF_'+('left_arm' if side=='leftArm' else 'right_arm'));armor=last('armor/chest/'+side)
        if not skin or not armor or not skin.get('actualCubes') or not armor.get('actualCubes'):continue
        s=skin['actualCubes'][0];t=armor['actualCubes'][0]
        sv=[v for face in s['polygonVerticesXYZUV'] for v in face];tv=[v for face in t['polygonVerticesXYZUV'] for v in face]
        translation=skin['fields'][:3];localSkin=[[v[i]+translation[i] for i in range(3)] for v in sv]
        sb=bounds(localSkin);ab=bounds(tv)
        sw=[world(skin['matrixColumnMajor'],v) for v in sv];aw=[world(armor['matrixColumnMajor'],v) for v in tv]
        # Every cuboid face contributes equally; mean of all24vertices is its geometric center.
        sc=[sum(v[i] for v in sw)/len(sw) for i in range(3)];ac=[sum(v[i] for v in aw)/len(aw) for i in range(3)]
        rows.append({'screenshot':c['screenshot'],'camera':c['camera'],'side':side,'omittedEvents':probe.get('omittedEvents'),
            'skinCubeClass':s['class'],'armorCubeClass':t['class'],'skinCubeRawMinMax':s['minMax'],'armorCubeRawMinMax':t['minMax'],
            'skinActualVerticesBoundsAfterRestTranslation':sb,'armorActualVertexBounds':ab,
            'armorMinusSkinVertexBounds':[x-y for x,y in zip(ab,sb)],'childRotation':skin['fields'][3:6],
            'actualCubeWorldCenterMaxDifference':max(abs(x-y) for x,y in zip(sc,ac)),
            'skinWorldCenter':sc,'armorWorldCenter':ac,
            'armorPolygonVerticesXYZUV':t['polygonVerticesXYZUV']})
result={'source':str(a.report.resolve()),'sha256':hashlib.sha256(a.report.read_bytes()).hexdigest(),'status':d.get('status'),
        'captures':len(d.get('captures',[])),'matchedActualCubePairs':len(rows),
        'interpretation':'Actual source cube polygon positions/UVs recorded at compile entry. Native armor inflation and wide/slim dimensions may differ; use recorded bounds and texture alpha. No production fix is implied solely by a visible bare forearm.',
        'rows':rows}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2));print('Matched actual cube pairs:',len(rows))
