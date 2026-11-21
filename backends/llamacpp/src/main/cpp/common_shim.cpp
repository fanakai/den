// The three helpers of llama.cpp's common library that json-schema-to-grammar.cpp uses, so Den does not build all of
// common (argument parsing, downloads, sampling presets) for it. Same behaviour as common.cpp.
#include <string>
#include <vector>

std::string string_join(const std::vector<std::string> & values, const std::string & separator) {
    std::string out;
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out += separator;
        out += values[i];
    }
    return out;
}

std::vector<std::string> string_split(const std::string & str, const std::string & delimiter) {
    std::vector<std::string> parts;
    size_t start = 0;
    size_t end = str.find(delimiter);
    while (end != std::string::npos) {
        parts.push_back(str.substr(start, end - start));
        start = end + delimiter.length();
        end = str.find(delimiter, start);
    }
    parts.push_back(str.substr(start));
    return parts;
}

std::string string_repeat(const std::string & str, size_t n) {
    if (n == 0) return "";
    std::string out;
    out.reserve(str.length() * n);
    for (size_t i = 0; i < n; ++i) out += str;
    return out;
}
