-- Names a write's transaction, so the bells rung in it are kept for its
-- answer and carry the tag on the channel (see groundwork.announce).
SELECT set_config('groundwork.tag', :tag, true) AS bell__tag
