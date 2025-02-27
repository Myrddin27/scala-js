#!/bin/sh

BASEDIR="`dirname $0`/.."

FULLVER="$1"

case $FULLVER in
  2.11.12)
    VER=2.11
    ;;
  2.12.8)
    VER=2.12
    ;;
  2.13.0)
    VER=2.13
    ;;
  2.12.1|2.12.2|2.12.3|2.12.4|2.12.5|2.12.6|2.12.7)
    echo "Ignoring checksizes for Scala $FULLVER"
    exit 0
    ;;
esac

REVERSI_PREOPT="$BASEDIR/examples/reversi/target/scala-$VER/reversi-fastopt.js"
REVERSI_OPT="$BASEDIR/examples/reversi/target/scala-$VER/reversi-opt.js"

REVERSI_PREOPT_SIZE=$(stat '-c%s' "$REVERSI_PREOPT")
REVERSI_OPT_SIZE=$(stat '-c%s' "$REVERSI_OPT")

gzip -c "$REVERSI_PREOPT" > "$REVERSI_PREOPT.gz"
gzip -c "$REVERSI_OPT" > "$REVERSI_OPT.gz"

REVERSI_PREOPT_GZ_SIZE=$(stat '-c%s' "$REVERSI_PREOPT.gz")
REVERSI_OPT_GZ_SIZE=$(stat '-c%s' "$REVERSI_OPT.gz")

case $FULLVER in
  2.11.12)
    REVERSI_PREOPT_EXPECTEDSIZE=442000
    REVERSI_OPT_EXPECTEDSIZE=95000
    REVERSI_PREOPT_GZ_EXPECTEDSIZE=60000
    REVERSI_OPT_GZ_EXPECTEDSIZE=27000
    ;;
  2.12.8)
    REVERSI_PREOPT_EXPECTEDSIZE=414000
    REVERSI_OPT_EXPECTEDSIZE=90000
    REVERSI_PREOPT_GZ_EXPECTEDSIZE=54000
    REVERSI_OPT_GZ_EXPECTEDSIZE=25000
    ;;
  2.13.0)
    REVERSI_PREOPT_EXPECTEDSIZE=552000
    REVERSI_OPT_EXPECTEDSIZE=121000
    REVERSI_PREOPT_GZ_EXPECTEDSIZE=74000
    REVERSI_OPT_GZ_EXPECTEDSIZE=34000
    ;;
esac

echo "Checksizes: Scala version: $FULLVER"
echo "Reversi preopt size = $REVERSI_PREOPT_SIZE (expected $REVERSI_PREOPT_EXPECTEDSIZE)"
echo "Reversi opt size = $REVERSI_OPT_SIZE (expected $REVERSI_OPT_EXPECTEDSIZE)"
echo "Reversi preopt gzip size = $REVERSI_PREOPT_GZ_SIZE (expected $REVERSI_PREOPT_GZ_EXPECTEDSIZE)"
echo "Reversi opt gzip size = $REVERSI_OPT_GZ_SIZE (expected $REVERSI_OPT_GZ_EXPECTEDSIZE)"

[ "$REVERSI_PREOPT_SIZE" -le "$REVERSI_PREOPT_EXPECTEDSIZE" ] && \
  [ "$REVERSI_OPT_SIZE" -le "$REVERSI_OPT_EXPECTEDSIZE" ] && \
  [ "$REVERSI_PREOPT_GZ_SIZE" -le "$REVERSI_PREOPT_GZ_EXPECTEDSIZE" ] && \
  [ "$REVERSI_OPT_GZ_SIZE" -le "$REVERSI_OPT_GZ_EXPECTEDSIZE" ]
