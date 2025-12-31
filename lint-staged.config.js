export default {
  'admin-ui/src/**/*.{ts,vue}': (filenames) => {
    // Convert absolute paths to relative paths from admin-ui
    const relativePaths = filenames.map(f => f.replace(/.*admin-ui\//, ''))
    return `cd admin-ui && npx eslint ${relativePaths.join(' ')}`
  }
}
