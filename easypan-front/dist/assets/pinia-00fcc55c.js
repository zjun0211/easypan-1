import{an as r,r as l,am as p}from"./@vue-57ff52f9.js";var u=!1;/*!
  * pinia v2.0.36
  * (c) 2023 Eduardo San Martin Morote
  * @license MIT
  */const f=Symbol();var s;(function(t){t.direct="direct",t.patchObject="patch object",t.patchFunction="patch function"})(s||(s={}));function m(){const t=r(!0),i=t.run(()=>l({}));let c=[],n=[];const a=p({install(e){a._a=e,e.provide(f,a),e.config.globalProperties.$pinia=a,n.forEach(o=>c.push(o)),n=[]},use(e){return!this._a&&!u?n.push(e):c.push(e),this},_p:c,_a:null,_e:t,_s:new Map,state:i});return a}export{m as c};
