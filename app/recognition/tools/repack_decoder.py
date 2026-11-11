"""Expose the pre-softmax logits tensor ('scaled_predictions') of a line-recognition decoder as an extra graph output."""
import sys

import flatbuffers
from ai_edge_litert import schema_py_generated as S
src,dst=sys.argv[1:3]
buf=open(src,'rb').read()
m=S.ModelT.InitFromPackedBuf(buf,0)
g=m.subgraphs[0]
idx=[i for i,t in enumerate(g.tensors) if t.name.decode()=='scaled_predictions']
assert len(idx)==1
outs=list(g.outputs)
if idx[0] not in outs: outs.append(idx[0])
g.outputs=outs
b=flatbuffers.Builder(len(buf)+1024)
b.Finish(m.Pack(b),b'TFL3')
open(dst,'wb').write(b.Output())
print(dst,len(buf),'->',len(b.Output()),'outputs',outs)
