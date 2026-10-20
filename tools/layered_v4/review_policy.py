"""Latest explicit human review supersedes old approvals. No GPU/environment changes."""
GENERATION_PAUSED=True
REJECTION_REASON='人物候选5及其Alpha已被人工Reject：人物语义区域缺失。固定珍珠2，仅允许新人物复审；禁止matte/background/final。'

def guard_generation():
    if GENERATION_PAUSED:raise RuntimeError(REJECTION_REASON)

def reject_legacy_person5():
    # Permanent revocation: even a later new-candidate approval must not revive this old path.
    raise RuntimeError(REJECTION_REASON)
