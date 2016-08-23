initSidebarItems({"struct":[["CommandInterpreter","The `CommandInterpreter` wraps each incoming `Command` inside an `Interpret` type with no response channel. This can then be sent to the `GlobalInterpreter`."],["EventInterpreter","The `EventInterpreter` listens for `Event`s and responds with `Command`s that can be sent to the `CommandInterpreter`."],["GlobalInterpreter","The `GlobalInterpreter` waits for incoming `Interpret` messages then interprets the contained `Command`, broadcasting the results as `Event` messages to `etx`. The final `Event` message is (optionally) sent to the `Interpret` listener."]],"trait":[["Interpreter","An `Interpreter` loops over any incoming values, on receipt of which it delegates to the `interpret` function which will respond with output values."]]});