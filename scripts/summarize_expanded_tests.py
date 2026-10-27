"""Keep expanded controlled-render matrices separate from actual live-action evidence."""
import csv, json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
RUNS=[ROOT/'.local/client/emfattachments-tests',ROOT/'.local/without-firstperson/.local/client/emfattachments-tests']
def reports(name):
 return sorted(path for folder in RUNS for path in folder.glob('*/'+name))
OUT=ROOT/'verification/expanded'
OUT.mkdir(parents=True,exist_ok=True)
summaries=[]
for path in reports('expanded-report.json'):
 d=json.loads(path.read_text());raw_rows=d.get('results',[]);raw_actions=d.get('actionMatrix',[])
 rows=list({(r.get('item'),r.get('slot'),r.get('slotIndex'),r.get('variant'),r.get('pose')):r for r in raw_rows}.values())
 if len(raw_actions)==len(raw_rows):
  raw_actions=[dict(a,item=r.get('item'),variant=r.get('variant')) for a,r in zip(raw_actions,raw_rows)]
 actions=list({(r.get('item'),r.get('slot'),r.get('slotIndex'),r.get('variant'),r.get('action')):r for r in raw_actions}.values())
 summary=dict(label=path.parent.name,kind='controlled-equipment-actions',finished=d.get('finished'),uniqueItems=len({r['item'] for r in rows}),slotCases=d.get('caseCount'),expectedRenders=d.get('expectedPoseRenders'),renders=len(rows),screenshots=d.get('screenshots'),exceptions=sum(bool(r.get('exception')) for r in rows),incomplete=sum(not r.get('completed') for r in rows),nonFinite=sum(r.get('invalidVertices',0)>0 for r in rows),sharedMutations=sum(r.get('mutatedParts',0)>0 for r in rows),anchorFailures=sum(sum(r.get(k,0) for k in ('gogglesAnchorFailures','torsoAnchorFailures','flowerAnchorFailures','flowerColorFailures')) for r in rows),flowerGeometryFailures=sum(r.get('flowerAnchorChecks',0)>0 and r.get('vertices')!=144 for r in rows),quiverDrawFailures=sum(r.get('item')=='supplementaries:quiver' and (r.get('quiverLayerCalls')!=1 or r.get('quiverVertices')!=64 or r.get('quiverInvalidVertices',0)!=0) for r in rows),discoveryErrors=d.get('discoveryErrors',[]),scope=d.get('scope'),evidence=str(path.relative_to(ROOT)))
 summary['actions']={a:dict(cases=sum(r['action']==a for r in actions),nativeUseTicks=sorted({r.get('useTicks',0) for r in actions if r['action']==a}),scales=sorted({r.get('scale') for r in actions if r['action']==a}),activeAnimations=sum(r.get('animationActive',False) for r in actions if r['action']==a),bridgeAttackHand=sum(r.get('bridgeAttackHand',False) for r in actions if r['action']==a)) for a in d.get('poses',[])}
 summary['rawRenderRecords']=len(raw_rows);summary['duplicateRenderRecords']=len(raw_rows)-len(rows)
 summaries.append(summary)
 keys=['item','slot','slotIndex','variant','pose','completed','renderer','rendererCalls','vertices','invalidVertices','mutatedParts','gogglesAnchorChecks','gogglesAnchorFailures','torsoAnchorChecks','torsoAnchorFailures','flowerAnchorChecks','flowerAnchorFailures','flowerColorFailures','quiverVertices','quiverBodyAnchorCalls','quiverLegAnchorCalls','exception']
 with (OUT/(path.parent.name+'-equipment.csv')).open('w',newline='',encoding='utf-8') as f:
  w=csv.DictWriter(f,fieldnames=keys,extrasaction='ignore');w.writeheader();w.writerows(rows)
 (OUT/(path.parent.name+'-actions.json')).write_text(json.dumps(actions,indent=2))
for path in reports('expanded-local-report.json'):
 d=json.loads(path.read_text());rows=d.get('rows',[])
 summaries.append(dict(label=path.parent.name,kind='controlled-local-render-contexts',status=d.get('status'),uniqueItems=d.get('uniqueItems'),slotCases=d.get('slotCases'),renderCases=d.get('renderCases'),failures=d.get('failures'),stateRestored=d.get('stateRestored'),hiddenCases=sum(r.get('expectedAccessoryHidden',False) for r in rows),normalZeroGeometryCases=sum(r.get('zeroGeometryInNormalContext',False) for r in rows),scope=d.get('scope'),evidence=str(path.relative_to(ROOT))))
 (OUT/(path.parent.name+'-local.json')).write_text(json.dumps(d,indent=2))
live_coverage={}
for path in reports('expanded-live-wearables-report.json'):
 d=json.loads(path.read_text());rows=d.get('cases',[]);shots=[dict(item=r.get('item'),slot=r.get('slot'),slotIndex=r.get('slotIndex'),variant=r.get('variant'),**shot) for r in rows for shot in r.get('captures',[])]
 summaries.append(dict(label=path.parent.name,kind='actual-live-wearable-eating',status=d.get('status'),uniqueItems=d.get('uniqueItems'),slotCases=d.get('caseCount'),startIndex=d.get('startIndex',0),scheduledCases=d.get('scheduledCases',d.get('caseCount')),nextCatalogIndex=d.get('nextCatalogIndex',d.get('completedCases')),completedCases=d.get('completedCases'),screenshots=d.get('captures'),failures=d.get('failures'),stateRestored=d.get('stateRestored'),neaRestored=d.get('neaRestored'),inputRestored=d.get('inputRestored'),nativeUseTicks=sorted({s.get('elapsedNativeUseTicks') for s in shots}),actualUseDurations=sorted({r.get('initialUseDuration') for r in rows if r.get('initialUseDuration') is not None}),gearFailures=sum(not s.get('correctGear',False) for s in shots),heldBreadDrawFailures=sum(not s.get('heldBreadDrawValid',False) for s in shots),bodyDrawFailures=sum(not s.get('bodyDrawValid',False) for s in shots),actualEatingArmChanges=sum(r.get('actualEatingArmPoseChanged',False) for r in rows),nativeInvisibleNotVisuallyAssessable=sum(r.get('visualStatus')=='NOT_VISUALLY_ASSESSABLE_NATIVE_INVISIBILITY' for r in rows),actualEatingModelObserved=sum(r.get('eatingModelVertices',0)>0 for r in rows),scope=d.get('scope'),evidence=str(path.relative_to(ROOT))))
 (OUT/(path.parent.name+'-live-wearables.json')).write_text(json.dumps(d,indent=2))
 keys=['item','slot','slotIndex','variant','file','view','elapsedNativeUseTicks','elapsedClientTicks','nativeTicksUsingItem','nativeTicksUsingItemDelta','baseUseDuration','actualUseDuration','remainingUseTicks','sameUseStack','correctGear','invisible','modelGeometryObserved','visualStatus','heldBreadDrawValid','bodyDrawValid']
 with (OUT/(path.parent.name+'-live-wearables.csv')).open('w',newline='',encoding='utf-8') as f:
  w=csv.DictWriter(f,fieldnames=keys,extrasaction='ignore');w.writeheader();w.writerows(shots)
 if 'live-wearables-final' in path.parent.name or 'live-wearables-resume' in path.parent.name:
  mode='firstperson-installed' if d.get('firstPersonInstalled') else 'firstperson-absent'
  coverage=live_coverage.setdefault(mode,dict(catalog={},completed={},attempts=[]))
  casekey=lambda r:(r.get('item'),r.get('slot'),r.get('slotIndex',r.get('index',0)),r.get('variant','default'))
  for r in d.get('catalog',[]):coverage['catalog'][casekey(r)]=r
  coverage['attempts'].append(dict(run=path.parent.name,status=d.get('status'),startIndex=d.get('startIndex',0),completedCases=d.get('completedCases'),stateRestored=d.get('stateRestored'),detail=d.get('detail')))
  for r in rows:
   captures=r.get('captures',[])
   if r.get('status') not in ('PASS','NATIVE_USE_PASS_VISUAL_NOT_ASSESSABLE') or len(captures)!=2:continue
   if not all(all(s.get(k,False) for k in ('correctGear','sameUseStack','heldBreadDrawValid','bodyDrawValid')) for s in captures):continue
   if any(any(s.get('attachmentRenderProbes',{}).get(k,0) for k in ('invalidVertices','mutatedParts','gogglesAnchorFailures','torsoAnchorFailures','flowerAnchorFailures','flowerColorFailures','quiverInvalidVertices','exception')) for s in captures):continue
   coverage['completed'][casekey(r)]=dict(item=r['item'],slot=r['slot'],slotIndex=r.get('slotIndex',0),variant=r.get('variant','default'),run=path.parent.name,visualStatus=r.get('visualStatus'),eatingModelVertices=r.get('eatingModelVertices'),nativeUseTicks=[s.get('elapsedNativeUseTicks') for s in captures],actualUseDuration=r.get('initialUseDuration'),images=[str((path.parent/s['file']).relative_to(ROOT)) for s in captures])
coverage_output={}
for mode,c in live_coverage.items():
 coverage_output[mode]=dict(requiredCases=len(c['catalog']),completedUniqueCases=len(c['completed']),completedUniqueItems=len({r['item'] for r in c['completed'].values()}),allEnumeratedNativeUseCasesObserved=set(c['catalog'])<=set(c['completed']),nativeInvisibleNotVisuallyAssessable=sum(r.get('visualStatus')=='NOT_VISUALLY_ASSESSABLE_NATIVE_INVISIBILITY' for r in c['completed'].values()),missing=[r for k,r in c['catalog'].items() if k not in c['completed']],cases=list(c['completed'].values()),attempts=c['attempts'])
(OUT/'live-wearable-coverage.json').write_text(json.dumps(coverage_output,indent=2))
for name in ('expanded-armor-report.json','movement-report.json','expanded-combat-report.json','warden-report.json','ragdoll-report.json','sequential-ability-report.json','tidal-grapple-report.json','hanging-report.json','shield-report.json'):
 for path in reports(name):
  d=json.loads(path.read_text());(OUT/(path.parent.name+'-'+name)).write_text(json.dumps(d,indent=2))
  summary=dict(label=path.parent.name,kind=name.removesuffix('-report.json'),status=d.get('status'),result=d.get('result'),caseCount=d.get('caseCount',len(d.get('cases',d.get('rows',[])))),screenshots=len(d.get('screenshots',d.get('captures',[]))),failures=d.get('failures',[]),stateRestored=d.get('stateRestored'),scope=d.get('scope'),evidence=str(path.relative_to(ROOT)))
  summary['restoration']={k:v for k,v in d.items() if k.endswith('Restored') or k in ('nativeSeatReleased','nativeClientSeatReleased','nativeClientVisibilityRestored','cleanupReactionGraceExpired')}
  if name=='tidal-grapple-report.json':
   summary.update(nativeLatchAndPull=d.get('nativeLatchAndPull'),nativeReleaseAndGravityRestored=d.get('nativeReleaseAndGravityRestored'),maximumRise=d.get('maximumRise'),impactControlStatus=d.get('impactControls',{}).get('status'),impactControls=len(d.get('impactControls',{}).get('rows',[]))+int('postReleaseImpactControl' in d),postReleaseOrdinaryImpactTracking=d.get('postReleaseImpactControl',{}).get('ordinaryImpactTrackingReached'))
  summaries.append(summary)
(OUT/'summary.json').write_text(json.dumps(summaries,indent=2))
print(json.dumps(summaries,indent=2))
