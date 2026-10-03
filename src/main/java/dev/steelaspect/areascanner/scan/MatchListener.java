package dev.steelaspect.areascanner.scan;

/** Told about every change to the match set, so the renderer can update just the affected sections. */
public interface MatchListener {
    /** A position was added, removed or changed group. */
    void onMatchChanged(long packedPos);

    /** All matches were dropped. */
    void onMatchesCleared();

    MatchListener NONE = new MatchListener() {
        @Override
        public void onMatchChanged(long packedPos) {
        }

        @Override
        public void onMatchesCleared() {
        }
    };
}
