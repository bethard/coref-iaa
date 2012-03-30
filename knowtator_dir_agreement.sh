#!/bin/bash

set -e

if [ $# -lt 2 ]
then
  echo "Usage: `basename $0` dir1 dir2 [--exclude mentionClass]"
  exit 1
fi

currentdir=`dirname $0`
dir1=$1
dir2=$2
shift 2

spans1file="`basename $dir1`.spans"
spans2file="`basename $dir2`.spans"

# use -nocompdaemon because of https://issues.scala-lang.org/browse/SI-4733
scala -nocompdaemon $currentdir/ParseKnowtatorCoref.scala $@ $dir1/* > $spans1file
scala -nocompdaemon $currentdir/ParseKnowtatorCoref.scala $@ $dir2/* > $spans2file

$currentdir/entity_span_agreement.pl $spans1file $spans2file
