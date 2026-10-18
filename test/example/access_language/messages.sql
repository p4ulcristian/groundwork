-- A conversation's messages, oldest first.
SELECT message__text FROM al_messages WHERE message__conversation_id = :conversation/id ORDER BY message__text
