'use strict';
const crypto = require('crypto');
console.log('CAMERA_TOKEN=' + crypto.randomBytes(32).toString('hex'));
console.log('VIEWER_TOKEN=' + crypto.randomBytes(32).toString('hex'));
