"""Small VISOR sparse-observation audit, with official frame mapping and measured FPS metadata."""
import csv
import hashlib
import io
import json
from pathlib import Path
import zipfile

import httpx
from PIL import Image
from live.ojt_data import canonical, digest
from live.ojt_download import RangeReader

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'https://data.bris.ac.uk/datasets/2v6cgv1x04ol22qp9rm9x2j6a7/'


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.output.resolve().is_relative_to(ROOT / '.runtime/ojt'):
        parser.error('output must be inside .runtime/ojt')
    args.output.mkdir(parents=True, exist_ok=False)
    media = args.output / 'media'; media.mkdir()
    mapping = json.loads((args.source/'frame_mapping.json').read_text())
    video_info = {r['video_id']:r for r in csv.DictReader((args.source/'EPIC_100_video_info.csv').open())}
    records, sources, splits, downloads = [], [], {}, []
    selection = [('P01_03','train','train','cup','컵'), ('P06_110','train','validation','pan','프라이팬'),
                 ('P32_07','val','test','toaster','토스터')]
    for video, official, target, noun, korean in selection:
        participant = video.split('_')[0]
        info = video_info[video]; fps = float(info['fps'])
        width, height = map(int,info['resolution'].split('x'))
        path = args.source/f'{video}.json'
        annotation = json.loads(path.read_text())
        observations = []
        for frame in annotation['video_annotations']:
            objects = [o for o in frame['annotations'] if o['name']==noun]
            if len(objects)!=1 or objects[0]['exhaustive']!='y': continue
            points = [p for segment in objects[0]['segments'] for p in segment]
            xs = [max(0,min(width,p[0])) for p in points]
            center = (min(xs)+max(xs))/2/width
            if min(abs(center-1/3),abs(center-2/3)) < .04:continue
            image = frame['image']; original = mapping[video][image['name']]
            milliseconds = int(round(int(Path(original).stem.split('_')[-1])/fps*1000))
            observations.append({'name':image['name'],'source_path':image['image_path'], 'at':milliseconds,
                                 'region':('왼쪽','가운데','오른쪽')[min(2,int(center*3))],
                                 'object_id':objects[0]['id'], 'subsequence':image['subsequence'], 'original_frame':original})
        observations.sort(key=lambda row:row['at'])
        pairs = [(a,b) for a,b in zip(observations,observations[1:]) if 0 < b['at']-a['at'] <= 30000][:4]
        if not pairs:raise ValueError(f'no supported temporal pairs for {video}')
        wanted = {item['name']:item for pair in pairs for item in pair}
        url = SOURCE+f'GroundTruth-SparseAnnotations/rgb_frames/{official}/{participant}/{video}.zip'
        with httpx.Client(timeout=40,follow_redirects=True) as client:
            remote = RangeReader(client,url,budget=32*1024**2)
            with zipfile.ZipFile(remote) as archive:
                members = {Path(item.filename).name:item for item in archive.infolist() if Path(item.filename).name in wanted}
                for name,item in wanted.items():
                    member=members[name]
                    if member.file_size>8*1024**2:raise ValueError('frame too large')
                    raw=archive.read(member)
                    with Image.open(io.BytesIO(raw)) as image:
                        if image.size!=(width,height):raise ValueError('source frame resolution mismatch')
                        image=image.convert('RGB');image.thumbnail((1280,1280));image.save(media/name,quality=95)
                    downloads.append({'url':url,'member':member.filename,'crc32':member.CRC,'raw_sha256':hashlib.sha256(raw).hexdigest(),
                                      'normalized_sha256':hashlib.sha256((media/name).read_bytes()).hexdigest()})
            print(video,'frames',len(wanted),'HTTP bytes',remote.transferred,flush=True)
        group=f'visor:participant:{participant}';splits[group]=target
        for index,(old,new) in enumerate(pairs):
            current={'path':f'media/{new["name"]}','view':'current','captured_at_ms':new['at']}
            history={'path':f'media/{old["name"]}','view':'history','captured_at_ms':old['at']}
            for task in ('current','history','unknown'):
                if task=='current':
                    images=[history,current];prompt=f'현재 제공된 화면에서 {korean}의 중심은 어느 구역에 있나요?'
                    response=f'현재 관측에서 {korean}의 중심은 화면 {new["region"]} 구역에 있습니다.'
                elif task=='history':
                    images=[history,current];prompt=f'이전에 제공된 화면에서 {korean}의 중심은 어느 구역에 있었나요?'
                    response=f'이전 제공 관측에서 {korean}의 중심은 화면 {old["region"]} 구역에 있었습니다.'
                else:
                    images=[history];prompt=f'지금 {korean}은 어디 있나요?'
                    response=f'현재 화면이 없어 지금 위치는 확인할 수 없습니다. 마지막 제공 관측에서는 화면 {old["region"]} 구역에 있었습니다.'
                row={'id':f'visor:{video}:{index}:{task}','group_id':group,'prompt':prompt,'response':response,
                     'question_at_ms':new['at'],'images':images};records.append(row)
                sources.append({'id':row['id'],'source':'visor','source_release':'sparse-Aug2022','source_split':official,
                    'source_url':SOURCE+f'GroundTruth-SparseAnnotations/annotations/{official}/{video}.json',
                    'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'source_item':video,
                    'leakage_keys':[group,f'visor:video:{video}'],'transform_version':'visor-provided-observation-ko-v1',
                    'review_status':'pending','record_sha256':digest(row),'fps':fps,
                    'frame_mapping_sha256':hashlib.sha256((args.source/'frame_mapping.json').read_bytes()).hexdigest(),
                    'video_info_sha256':hashlib.sha256((args.source/'EPIC_100_video_info.csv').read_bytes()).hexdigest(),
                    'observations':[old,new],'task':task,'human_approved':False,
                    'scope':'provided class observations; not persistent object identity or physical motion'})
    for name,rows in [('records.jsonl',records),('provenance.jsonl',sources),('downloads.jsonl',downloads)]:
        (args.output/name).write_text(''.join(canonical(row)+'\n' for row in rows),encoding='utf-8')
    (args.output/'split-map.json').write_text(canonical(splits)+'\n')
    report={'records':len(records),'frames':len(downloads),'model_acceptance':False,'human_approved':False,
            'status':'temporal_annotation_audit_only','source':SOURCE}
    (args.output/'manifest.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)


if __name__=='__main__':main()
