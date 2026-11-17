import test from 'node:test'
import assert from 'node:assert/strict'
import {layoutRelayFlow} from '../relayFlow.js'
test('Sankey merges shared tools and conserves token totals across every column',()=>{
 const result=layoutRelayFlow([{department:'a',tool:'t',model:'m',tokens:30},{department:'b',tool:'t',model:'m',tokens:70}])
 assert.equal(result.total,100)
 for(let i=0;i<3;i++)assert.equal(result.nodes.filter(n=>n.column===i).reduce((s,n)=>s+n.value,0),100)
 assert.equal(result.nodes.filter(n=>n.column===1).length,1)
 assert.equal(result.links.length,3)
 assert.equal(layoutRelayFlow([{tokens:NaN}]).total,0)
})
