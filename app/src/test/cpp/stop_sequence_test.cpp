#include <cassert>
#include <string>

static bool apply_stop_sequence(std::string & generated_text, const std::string & stop_sequence) {
    const size_t stop_pos = generated_text.find(stop_sequence);
    if (stop_pos == std::string::npos) {
        return false;
    }
    generated_text.erase(stop_pos);
    return true;
}

int main() {
    {
        std::string text = "ABC<END>XYZ";
        assert(apply_stop_sequence(text, "<END>"));
        assert(text == "ABC");
    }

    {
        std::string text = "ABC<END>";
        assert(apply_stop_sequence(text, "<END>"));
        assert(text == "ABC");
    }

    {
        std::string text = "ABCXYZ";
        assert(!apply_stop_sequence(text, "<END>"));
        assert(text == "ABCXYZ");
    }

    // Simulate a stop sequence split across token pieces.
    {
        std::string text;
        const char * pieces[] = {"ABC", "<", "EN", "D>", "XYZ"};
        for (const char * piece : pieces) {
            text += piece;
            if (apply_stop_sequence(text, "<END>")) {
                break;
            }
        }
        assert(text == "ABC");
    }

    return 0;
}
