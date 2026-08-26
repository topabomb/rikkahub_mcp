# Third-party notices

This file records third-party components added or materially updated by the fork. It supplements
the license metadata packaged by Gradle dependencies and the source-specific provenance files.

| Component | Version | License | Source / provenance |
| --- | --- | --- | --- |
| SnakeYAML | 2.6 | Apache-2.0 | <https://bitbucket.org/snakeyaml/snakeyaml>; resolved through the Gradle dependency catalog |
| PRoot | 5.1.107.92 | GPL-2.0-or-later | [`workspace/PROOT.md`](workspace/PROOT.md) and [`workspace/proot-lock.json`](workspace/proot-lock.json) |
| libtalloc | 2.4.3 | LGPL-3.0-or-later | Statically linked into the recorded PRoot artifacts; source hash is pinned in `workspace/proot-lock.json` |
| libandroid-shmem | 0.7 | BSD-3-Clause | Statically linked into the recorded PRoot artifacts; source hash is pinned in `workspace/proot-lock.json` |

The checked-in PRoot executables are byte-identical to upstream app commit
`f4508dfac2255cf83e75859a8fe37dd7da6778a3`. Source locations, archive hashes, the candidate recipe
and the fail-closed rebuild command are recorded in `workspace/PROOT.md`,
`workspace/proot-lock.json` and `workspace/tools/build-proot.sh`. The local rebuild has not been run,
so bit-for-bit reproducibility from that recipe is not claimed.

Because libtalloc is statically linked, a source URL and rebuild recipe alone do not complete the
LGPL-3.0-or-later distribution obligations. A release containing these PRoot artifacts must also
provide the Corresponding Application Code in a form that permits relinking with a modified
libtalloc, plus applicable installation information, or change to another compliant linkage model.
The release compliance bundle is a separate release gate and is not claimed complete by this file.

## libandroid-shmem BSD-3-Clause notice

Copyright (c) 2013, Sergii Pylypenko
Copyright (c) 2017, Fredrik Fornwall
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted
provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this list of conditions
  and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice, this list of
  conditions and the following disclaimer in the documentation and/or other materials provided
  with the distribution.
* Neither the name of the {organization} nor the names of its contributors may be used to endorse
  or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
