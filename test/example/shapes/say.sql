-- A message into a conversation. Its sides aren't named: they are the conversation's.
INSERT INTO sh_messages (message__conversation_id, message__text)
VALUES (:conversation/id, :message/text)
RETURNING message__id
