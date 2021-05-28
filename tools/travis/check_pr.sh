#!/usr/bin/env bash

#
# Copyright 2018 New Vector Ltd
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

branch=${TRAVIS_BRANCH}

# echo ${TRAVIS_BRANCH}

# If not on develop, exit, else we cannot get the list of modified files
# It is ok to check only when on develop branch
if [[ "${branch}" -eq 'develop' ]]; then
    echo "Check that a file has been added to /newsfragment"
else
    echo "Not on develop branch"
    exit 0
fi

# git status

listOfModifiedFiles=`git diff --name-only HEAD ${branch}`

# echo "List of modified files by this PR:"
# echo ${listOfModifiedFiles}


if [[ ${listOfModifiedFiles} = *"newsfragment"* ]]; then
  echo "A file has been added to /newsfragment!"
else
  echo "❌ Please add a file describing your changes in /newsfragment. See https://github.com/vector-im/element-android/blob/develop/CONTRIBUTING.md#changelog"
  exit 1
fi
